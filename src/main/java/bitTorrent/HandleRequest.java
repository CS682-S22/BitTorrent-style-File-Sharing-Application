package bitTorrent;

import be.adaxisoft.bencode.BEncodedValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;

public class HandleRequest {

    private static final Logger LOGGER = LogManager.getLogger(HandleRequest.class.getName());

    public static void handleRequest(BitSet iHave, byte[] hashValue, Socket socket, Map<String, BEncodedValue> info, String myId, int port, Map<byte[], Socket>clientSockets) {
        byte[] peerId;
        DataInputStream in;
        DataOutputStream out;
        try {
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

            LOGGER.info("Checking handshake.");
            //check handshake
            if (in.read() != 19) {
                socket.close();
            }
            if (!Arrays.equals(in.readNBytes("BitTorrent protocol".length()), "BitTorrent protocol".getBytes())) {
                socket.close();
            }
            in.readNBytes(8);
            peerId = in.readNBytes(20);
            if (!Arrays.equals(peerId, hashValue)) {
                return;
            }
        } catch (IOException e) {
            LOGGER.info("Server handshake error, return");
            return;
        }

        try {
            in.readNBytes(20);
            LOGGER.info("Received handshake from client.");
            clientSockets.put(peerId, socket);

            //reply handshake
            out.write(19);
            out.write("BitTorrent protocol".getBytes());
            out.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 0});
            out.write(hashValue);
            out.write(myId.getBytes());
            out.flush();
            LOGGER.info("Reply handshake to client.");

            // sending bitfield
            out.writeInt(1 + iHave.toByteArray().length);
            out.write(5);
            out.write(iHave.toByteArray());
            out.flush();
            LOGGER.info("Send bitfield to client.");

            while(true) {

                int len = in.readInt();
                if (len == 0) { //keep-alive
                    continue;
                }
                int id = in.read();

                if(id == 6) {

                    int index = in.readInt();
                    int begin = in.readInt();
                    int length = in.readInt();

                    try(FileInputStream inStream = new FileInputStream(Path.of("target", String.valueOf(port), info.get("name").getString()).toFile())) {
                        if (iHave.get(index)) {
                            inStream.skipNBytes(((long) index * (info.get("piece length").getInt())) + begin);
                            byte[] block = inStream.readNBytes(length);
                            synchronized (clientSockets.get(peerId)) {
                                //piece: <len=0009+X><id=7><index><begin><block>
                                out.writeInt(9 + length);
                                out.write(7);
                                out.writeInt(index);
                                out.writeInt(begin);
                                out.write(block);
                                out.flush();
                                LOGGER.info("Send piece.");
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.info("Broken pipe");
            clientSockets.remove(peerId);
        }
    }
}
