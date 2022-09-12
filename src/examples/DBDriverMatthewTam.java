public class DBDriverMatthewTam {
    /**
     * @param args
     * @throws DatabaseException
     */
    public static void main (String[] args) throws DatabaseException {
            DatabaseConnectionPool Pool1 = new DatabaseConnectionPool("pool1", "jdbc:oracle:thin:@localhost:1521:orcl", "scott", "tiger", 10, 5);
            DatabaseConnection dc1 = Pool1.acquireConnection(40);
    }
}


