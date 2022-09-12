public class DBDriverMatthewTam {
    /**
     * @param args
     * @throws DatabaseException
     */
    public static void main (String[] args) throws DatabaseException {
            DatabaseConnectionPool Pool1 = new DatabaseConnectionPool("pool1", "jdbc:oracle:thin:@localhost:1521:orcl", "scott", "tiger", 3, 1);
            DatabaseConnection dc1 = Pool1.acquireConnection(40);
    }
}


