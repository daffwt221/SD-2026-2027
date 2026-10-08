package tcp01;

import java.io.*;
import java.net.*;

public class TCPServer {
    public static void main (String[] args){
        try {
            int port = 7896;
            ServerSocket listenSocket = new ServerSocket(port);
            while(true){
                Socket clientSocket = listenSocket.accept(); // block until a client connects
                Connection c = new Connection(clientSocket);
            }
        }
        catch(IOException e) {
            System.out.println("Listen :"+ e.getMessage());
        }
    }
}
