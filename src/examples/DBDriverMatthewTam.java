import ConnectionPool;
class DBDriverMatthewTam {
    public static void main(String[] args) {
        ConnectionPool pool = new ConnectionPool("jdbc:odbc:MatthewTam", "MatthewTam", "MatthewTam");
        Connection con = pool.getConnection();
        Statement stmt = con.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM MatthewTam");
        while (rs.next()) {
            System.out.println(rs.getString(1));
        }
        rs.close();
        stmt.close();
        con.close();
    }
}