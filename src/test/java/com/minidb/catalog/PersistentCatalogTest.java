package com.minidb.catalog;

import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistentCatalogTest {

    private static final String CATALOG_FILE = "data/catalog.dat";

    @BeforeEach
    void setUp() throws IOException {
        Files.deleteIfExists(Paths.get(CATALOG_FILE));
    }

    @Test
    void createThenFindTableAndColumns() throws MiniDbException {
        PersistentCatalog catalog = new PersistentCatalog();
        TableDef def = new TableDef("users", List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 32)
        ));
        catalog.createTable(def);

        assertTrue(catalog.findTable("users").isPresent());
        assertEquals("users", catalog.findTable("users").get().tableName());
        assertEquals(DataType.INT, catalog.getType("users", "id"));
        assertEquals(DataType.VARCHAR, catalog.getType("users", "name"));
    }

    @Test
    void persistAndReload() throws MiniDbException {
        PersistentCatalog catalog = new PersistentCatalog();
        catalog.createTable(new TableDef("users", List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 32)
        )));
        catalog.createTable(new TableDef("orders", List.of(
                new ColumnDef("order_id", DataType.INT, 0),
                new ColumnDef("user_id", DataType.INT, 0)
        )));

        assertTrue(Files.exists(Paths.get(CATALOG_FILE)));

        PersistentCatalog reloaded = new PersistentCatalog();

        assertTrue(reloaded.findTable("users").isPresent());
        assertTrue(reloaded.findTable("orders").isPresent());

        assertEquals(DataType.INT, reloaded.getType("users", "id"));
        assertEquals(DataType.VARCHAR, reloaded.getType("users", "name"));
        assertEquals(DataType.INT, reloaded.getType("orders", "order_id"));
        assertEquals(DataType.INT, reloaded.getType("orders", "user_id"));

        List<TableDef> allTables = reloaded.getAllTables();
        assertEquals(2, allTables.size());
    }

    @Test
    void duplicateCreateThrowsSemantic() throws MiniDbException {
        PersistentCatalog catalog = new PersistentCatalog();
        TableDef def = new TableDef("users", List.of(
                new ColumnDef("id", DataType.INT, 0)
        ));
        catalog.createTable(def);

        MiniDbException e = assertThrows(MiniDbException.class,
                () -> catalog.createTable(new TableDef("USERS", List.of(
                        new ColumnDef("id", DataType.INT, 0)
                ))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        // 检查异常消息包含表名（忽略大小写）
        assertTrue(e.getMessage().toLowerCase().contains("users"),
                "异常消息应该包含表名: " + e.getMessage());
    }

    @Test
    void reloadedDuplicateCreateThrowsSemantic() throws MiniDbException {
        PersistentCatalog catalog = new PersistentCatalog();
        catalog.createTable(new TableDef("users", List.of(
                new ColumnDef("id", DataType.INT, 0)
        )));

        PersistentCatalog reloaded = new PersistentCatalog();
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> reloaded.createTable(new TableDef("users", List.of(
                        new ColumnDef("id", DataType.INT, 0)
                ))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
    }

    @Test
    void findMissingReturnsEmpty() throws MiniDbException {
        PersistentCatalog catalog = new PersistentCatalog();
        assertTrue(catalog.findTable("non_existent").isEmpty());
        assertTrue(catalog.findColumn("non_existent", "col").isEmpty());
    }

    @Test
    void getTypeMissingColumnThrows() throws MiniDbException {
        PersistentCatalog catalog = new PersistentCatalog();
        catalog.createTable(new TableDef("users", List.of(
                new ColumnDef("id", DataType.INT, 0)
        )));

        MiniDbException e = assertThrows(MiniDbException.class,
                () -> catalog.getType("users", "age"));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
    }

    @Test
    void getTypeMissingTableThrows() throws MiniDbException {
        PersistentCatalog catalog = new PersistentCatalog();
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> catalog.getType("non_existent", "id"));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
    }

    @Test
    void identifiersAreCaseInsensitive() throws MiniDbException {
        PersistentCatalog catalog = new PersistentCatalog();
        catalog.createTable(new TableDef("Student", List.of(
                new ColumnDef("ID", DataType.INT, 0),
                new ColumnDef("Name", DataType.VARCHAR, 16)
        )));

        assertTrue(catalog.findTable("student").isPresent());
        assertTrue(catalog.findTable("STUDENT").isPresent());
        assertEquals(DataType.INT, catalog.getType("STUDENT", "id"));
        assertTrue(catalog.findColumn("student", "NAME").isPresent());
    }
}