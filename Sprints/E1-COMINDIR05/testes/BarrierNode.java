package raft04;

/** Barreira de arranque do ensaio 9(b); não altera o protocolo nem sincroniza relógios entre máquinas. */
public final class BarrierNode {
    public static void main(String[] args)throws Exception{
        Class.forName("raft04.PartitionNode");
        try(IpfsPubSub warm=new IpfsPubSub(System.getProperty("raft04.api","http://127.0.0.1:5001/api/v0"))){ }
        long at=Long.getLong("raft04.startAt",0);
        while(System.currentTimeMillis()<at)Thread.sleep(Math.max(1,Math.min(10,at-System.currentTimeMillis())));
        PartitionNode.main(args);
    }
}
