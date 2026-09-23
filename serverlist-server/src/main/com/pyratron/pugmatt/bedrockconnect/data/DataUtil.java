package main.com.pyratron.pugmatt.bedrockconnect.data;

import main.com.pyratron.pugmatt.bedrockconnect.BedrockConnect;
import main.com.pyratron.pugmatt.bedrockconnect.server.BCPlayer;
import main.com.pyratron.pugmatt.bedrockconnect.server.PacketHandler;
import main.com.pyratron.pugmatt.bedrockconnect.server.gui.UIComponents;

import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DataUtil {

    private static final Path PLAYERS_DIR = Paths.get("players");

    private Database database;

    // Single worker for all database work: avoids spawning a thread per query and
    // prevents concurrent use of the shared JDBC connection (which is not thread-safe)
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BedrockConnect-Database");
        t.setDaemon(true);
        return t;
    });

    // Some optional columns were added in a later version, consider their existance during data retrieval/updating
    private boolean viewedMotdExists = false;

    public DataUtil(Database database) {
        this.database = database;
        if (BedrockConnect.getConfig().isUsingDatabase()) {
            try {
                createTables((database.getType() == DatabaseTypes.postgres));

                // Check for existance of columns added in later versions
                DatabaseMetaData metaData = database.getConnection().getMetaData();
                viewedMotdExists = columnExists(metaData, "viewedMotd");

                // If configuration is set to consider motd viewing date but viewedMotd column does not currently exist, then add viewedMotd column to table
                if (BedrockConnect.getConfig().isMotdCooldownEnabled() && !viewedMotdExists) {
                    String dateType = "DATETIME";
                    if (database.getType() == DatabaseTypes.postgres) dateType = "TIMESTAMP";

                    try (Statement stmt = database.getConnection().createStatement()) {
                        stmt.execute("ALTER TABLE servers ADD viewedMotd " + dateType + ";");
                    }

                    viewedMotdExists = true;
                }
            } catch (Exception e) {
                errorAlert(e);
            }
        }
    }

    private void createTables(boolean postgres) throws SQLException {
        // Create BedrockConnect related tables if they do not exist
        String sqlCreate = "";
        if (!postgres)
            sqlCreate = "CREATE TABLE IF NOT EXISTS servers"
                    + "  (id         INTEGER PRIMARY KEY AUTO_INCREMENT,"
                    + "   uuid            TEXT,"
                    + "   name            TEXT,"
                    + "   servers         TEXT,"
                    + "   serverLimit     INTEGER,"
                    + "   viewedMotd      DATETIME,"
                    + "   INDEX (uuid(255))"
                    + ");";
        else
            sqlCreate = "DO $$\n" +
                    "BEGIN\n" +
                    "    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'servers') THEN\n" +
                    "        CREATE TABLE servers (\n" +
                    "            id SERIAL PRIMARY KEY,\n" +
                    "            uuid TEXT,\n" +
                    "            name TEXT,\n" +
                    "            servers TEXT,\n" +
                    "            serverLimit INTEGER,\n" +
                    "            viewedMotd TIMESTAMP\n" +
                    "        );\n" +
                    "    END IF;\n" +
                    "\n" +
                    "    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_uuid') THEN\n" +
                    "        CREATE INDEX idx_uuid ON servers(uuid);\n" +
                    "    END IF;\n" +
                    "END $$;\n";

        try (Statement stmt = database.getConnection().createStatement()) {
            stmt.execute(sqlCreate);
        }
    }

    private BCPlayer getPlayer(ResultSet rs, String name, String uuid, BedrockServerSession session) {
        try {
            LocalDateTime viewedMotd = null;
            if (viewedMotdExists) {
                Timestamp ts = rs.getTimestamp("viewedMotd");
                if (ts != null)
                    viewedMotd = ts.toLocalDateTime();
            } 

            BCPlayer p = new BCPlayer(name, uuid, session, UIComponents.getFormData(rs.getString("servers")),
                    rs.getInt("serverLimit"), false, viewedMotd);
            return p;
        } catch (SQLException e) {
            errorAlert(e);
            return null;
        }
    }

    private void createPlayerRecord(String uuid, String name, BedrockServerSession session, PacketHandler packetHandler) {
        int serverLimit = Integer.parseInt(BedrockConnect.getConfig().getServerLimit());
        try (PreparedStatement s = database.getConnection()
                .prepareStatement("INSERT INTO servers (uuid, name, serverLimit) VALUES (?, ?, ?)")) {
            s.setString(1, uuid);
            s.setString(2, BedrockConnect.getConfig().canStoreDisplayNames() ? name : "");
            s.setInt(3, serverLimit);
            s.executeUpdate();
            BedrockConnect.logger.info("Added new user " + name + " (xuid: " + uuid + ") to Database");
            BCPlayer pl = new BCPlayer(name, uuid, session, new ArrayList<>(), serverLimit, true, null);
            packetHandler.setPlayer(pl);
            BedrockConnect.getServer().addPlayer(pl);
        } catch (Exception e) {
            errorAlert(e);
            session.disconnect(BedrockConnect.getConfig().getLanguage().getWording("disconnect", "dataError"));
        }
    }

    // Perform a look up of user to confirm if they already exist.
    // If they already exist, simply grab the info for our player object (Also
    // update stored display name for player, if enabled)
    // If they do not exist, create a new record for the player
    public void initializePlayerData(String uuid, String name, BedrockServerSession session, PacketHandler packetHandler) {
        if (BedrockConnect.getConfig().isUsingDatabase()) {
            dbExecutor.execute(() -> {
                try (PreparedStatement getUser = database.getConnection()
                        .prepareStatement("SELECT * FROM servers WHERE uuid = ?;")) {
                    getUser.setString(1, uuid);
                    boolean exists;
                    try (ResultSet rs = getUser.executeQuery()) {
                        exists = rs.next();
                        if (exists) {
                            String storedName = rs.getString("name");
                            if (BedrockConnect.getConfig().canStoreDisplayNames() && !name.equals(storedName)) {
                                try (PreparedStatement updateName = database.getConnection()
                                        .prepareStatement("UPDATE servers SET name = ? WHERE uuid = ?")) {
                                    updateName.setString(1, name);
                                    updateName.setString(2, uuid);
                                    updateName.executeUpdate();
                                }
                            }
                            BCPlayer p = getPlayer(rs, name, uuid, session);
                            packetHandler.setPlayer(p);
                            if (p != null)
                                BedrockConnect.getServer().addPlayer(p);
                        }
                    }
                    if (!exists) {
                        createPlayerRecord(uuid, name, session, packetHandler);
                    }
                } catch (Exception e) {
                    errorAlert(e);
                    session.disconnect(BedrockConnect.getConfig().getLanguage().getWording("disconnect", "dataError"));
                }
            });
        } else {
            try {
                Files.createDirectories(PLAYERS_DIR);
                File plyrFile = playerFile(uuid).toFile();
                if (plyrFile.createNewFile()) {
                    JSONObject jo = new JSONObject();
                    jo.put("uuid", uuid);
                    if (BedrockConnect.getConfig().canStoreDisplayNames()) {
                        jo.put("name", name);
                    }
                    jo.put("serverLimit", BedrockConnect.getConfig().getServerLimit());
                    jo.put("servers", new JSONArray());

                    writePlayerFile(uuid, jo);

                    BedrockConnect.logger.info("Added new user " + name + " (xuid: " + uuid + ")");

                    BCPlayer pl = new BCPlayer(name, uuid, session, new ArrayList<>(), Integer.parseInt(BedrockConnect.getConfig().getServerLimit()), true, null);
                    packetHandler.setPlayer(pl);
                    BedrockConnect.getServer().addPlayer(pl);
                } else {
                    JSONObject jo = readPlayerFile(uuid);

                    int serverLimit = Integer.parseInt(String.valueOf(jo.get("serverLimit")));

                    JSONArray servers = (JSONArray) jo.get("servers");

                    LocalDateTime viewedMotd = null;
                    if (jo.containsKey("viewedMotd")) {
                        viewedMotd = LocalDateTime.parse((String) jo.get("viewedMotd"));
                    }

                    BCPlayer p = new BCPlayer(name, uuid, session, servers, serverLimit, false, viewedMotd);
                    packetHandler.setPlayer(p);
                    BedrockConnect.getServer().addPlayer(p);
                }
            } catch (Exception e) {
                BedrockConnect.logger.error("An error occurred saving to player file", e);
            }
        }
    }

    public void setValueString(String column, String value, List<String> serverList, String uuid) {
        if (BedrockConnect.getConfig().isUsingDatabase()) {
            dbExecutor.execute(() -> {
                try (PreparedStatement s = database.getConnection()
                        .prepareStatement("UPDATE servers SET " + column + "= ? WHERE uuid= ?")) {
                    s.setString(1, value);
                    s.setString(2, uuid);

                    s.executeUpdate();
                } catch (Exception e) {
                    errorAlert(e);
                }
            });
        } else {
            try {
                JSONObject jo = readPlayerFile(uuid);

                jo.put("servers", serverList);

                writePlayerFile(uuid, jo);
            } catch (Exception e) {
                BedrockConnect.logger.error("An error occurred saving to player file", e);
            }
        }
    }

    public void setValueInt(String column, Integer value, String uuid) {
        dbExecutor.execute(() -> {
            try (PreparedStatement s = database.getConnection()
                    .prepareStatement("UPDATE servers SET " + column + "= ? WHERE uuid= ?")) {
                s.setInt(1, value);
                s.setString(2, uuid);

                s.executeUpdate();
            } catch (Exception e) {
                errorAlert(e);
            }
        });
    }

     public void setViewedMotd(String uuid) {
         if (BedrockConnect.getConfig().isUsingDatabase()) {
            if (viewedMotdExists) {
                dbExecutor.execute(() -> {
                    try (PreparedStatement s = database.getConnection()
                            .prepareStatement("UPDATE servers SET viewedMotd = ? WHERE uuid= ?")) {
                        s.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
                        s.setString(2, uuid);

                        s.executeUpdate();
                    } catch (Exception e) {
                        errorAlert(e);
                    }
                });
            }
        } else {
            try {
                JSONObject jo = readPlayerFile(uuid);

                jo.put("viewedMotd", LocalDateTime.now().toString());

                writePlayerFile(uuid, jo);
            } catch (Exception e) {
                BedrockConnect.logger.error("An error occurred saving to player file", e);
            }
        }
    }

    /**
     * Run a task on the database worker thread (e.g. connection keep-alive),
     * so it never races with queries on the shared connection
     */
    public void runOnDatabaseThread(Runnable task) {
        dbExecutor.execute(task);
    }

    private static Path playerFile(String uuid) {
        return PLAYERS_DIR.resolve(uuid + ".json");
    }

    private static JSONObject readPlayerFile(String uuid) throws Exception {
        // InputStreamReader substitutes malformed bytes instead of throwing (older files may use the platform charset)
        try (Reader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(playerFile(uuid)), StandardCharsets.UTF_8))) {
            return (JSONObject) new JSONParser().parse(reader);
        }
    }

    // Write to a temp file first then move it into place, so a crash mid-write can't corrupt player data
    private static void writePlayerFile(String uuid, JSONObject jo) throws IOException {
        Path target = playerFile(uuid);
        Path tmp = target.resolveSibling(uuid + ".json.tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            writer.write(jo.toJSONString());
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean columnExists(DatabaseMetaData meta, String columnName) throws SQLException {
        try (ResultSet cols = meta.getColumns(null, "%", "servers", "%")) {
            while (cols.next()) {
                String col = cols.getString("COLUMN_NAME");
                if (col.equalsIgnoreCase(columnName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void errorAlert(Exception e) {
        BedrockConnect.logger.error("A database error has occured", e);
    }

}