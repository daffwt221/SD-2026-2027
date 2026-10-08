package tcp01;

import java.io.*;
import java.net.*;

public class Connection extends Thread {
    ObjectInputStream in;
    DataOutputStream out;
    Socket clientSocket;

    public Connection (Socket aClientSocket) {
        try {
            clientSocket = aClientSocket;
            in = new ObjectInputStream(clientSocket.getInputStream());
            out = new DataOutputStream(clientSocket.getOutputStream());
            this.start();
        }
        catch(IOException e) {
            System.out.println("Connection:"+e.getMessage());
        }
    }
    @Override 

    public void run(){
        try {	
            Person Person = (Person) in.readObject();	 // an echo server
            System.out.println("Received: "+Person.getName());
            out.writeUTF(Person.getName());		// send it back to the client
        }
        catch(EOFException e) {
            System.out.println("EOF:"+e.getMessage());
        }
        catch(IOException e) {
            System.out.println("IO:"+e.getMessage());
        }
        catch(ClassNotFoundException e) {
            System.out.println("Class not found:"+e.getMessage());
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
