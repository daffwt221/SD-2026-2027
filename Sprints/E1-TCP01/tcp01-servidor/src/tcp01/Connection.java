package tcp01;

import java.io.*;
import java.net.*;

public class Connection extends Thread {
    DataInputStream in;
    DataOutputStream out;
    Socket clientSocket;

    public Connection (Socket aClientSocket) {
        try {
            clientSocket = aClientSocket;
            in = new DataInputStream(clientSocket.getInputStream());
            out = new DataOutputStream(clientSocket.getOutputStream());
            this.start();
        }
        catch(IOException e) {
            System.out.println("Connection:"+e.getMessage());
        }
    }
    @Override 

    public void run(){
        try {			                 // an echo server
            String data = in.readUTF();	// read a line of data from the stream
            System.out.println("Received: "+data);
            out.writeUTF(data);		// send it back to the client
        }
        catch(EOFException e) {
            System.out.println("EOF:"+e.getMessage());
        }
        catch(IOException e) {
            System.out.println("IO:"+e.getMessage());
        }
        finally { 
            try {
                clientSocket.close();
            }
            catch (IOException e){
                /*close failed*/
            }
        }
    }       
}