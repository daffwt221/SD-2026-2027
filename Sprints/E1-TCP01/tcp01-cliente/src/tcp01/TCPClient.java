package tcp01;

import java.io.*;
import java.net.*;

public class TCPClient {
    public static void main(String args[]) {
        try {
            int serverPort = 7896;
            Socket socket = new Socket("localhost", serverPort);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeUTF("Hello from the client");
            String data = in.readUTF();
            System.out.println("Received: "+data);
            socket.close();
        }
        catch (UnknownHostException e) {
            System.out.println("Sock:"+e.getMessage());
        }
        catch (EOFException e) {
            System.out.println("EOF:"+e.getMessage());
        }
        catch (IOException e) {
            System.out.println("IO:"+e.getMessage());
        }
    }
}
