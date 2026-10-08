package tcp01;

import java.io.*;
import java.net.*;

public class TCPClient {
    public static void main(String args[]) {
        Socket socket = null;
        try {
            int serverPort = 7896;
            socket = new Socket("localhost", serverPort);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.writeObject(new Person("Emilio", 2000));
            out.flush(); // send the message
            String data = in.readUTF();
            System.out.println("Received: "+data);
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
        finally {
            if (socket != null)
                try {
                    socket.close();
                }
                catch (IOException e){
                    System.out.println("close:"+e.getMessage());
                }
        }
    }
}
