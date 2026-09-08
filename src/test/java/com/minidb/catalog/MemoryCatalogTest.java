package com.minidb.catalog;

import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCatalogTest {

    private MemoryCatalog catalogWithUsers() throws MiniDbException {
        MemoryCatalog catalog = new MemoryCatalog();
        catalog.createTable(new TableDef("users", List.of(
            new ColumnDef("id", DataType.INT, 0),
            new ColumnDef("score", DataType.FLOAT, 0),
            new ColumnDef("name", DataType.VARCHAR, 32))));
        return catalog;
    }

    @Test
    void createThenFindTableAndColumns() throws MiniDbException {
        MemoryCatalog catalog = catalogWithUsers();

        assertTrue(catalog.findTable("users").isPresent());
        assertEquals("users", catalog.findTable("users").get().tableName());

        assertTrue(catalog.findColumn("users", "id").isPresent());
        assertEquals(DataType.INT, catalog.getType("users", "id"));
        assertEquals(DataType.FLOAT, catalog.getType("users", "score"));
        assertEquals(DataType.VARCHAR, catalog.getType("users", "name"));
    }

    @Test
    void duplicateCreateThrowsSemanticWithTableName() throws MiniDbException {
        MemoryCatalog catalog = catalogWithUsers();

        MiniDbException e = assertThrows(MiniDbException.class,
            () -> catalog.createTable(new TableDef("users", List.of(
                new ColumnDef("id", DataType.INT, 0)))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("users"));
    }

    @Test
    void getTypeMissingColumnThrowsSemanticWithColumnName() throws MiniDbException {
        MemoryCatalog catalog = catalogWithUsers();

        MiniDbException e = assertThrows(MiniDbException.class,
            () -> catalog.getType("users", "age"));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("age"));
    }

    @Test
    void getTypeMissingTableThrowsSemanticWithTableName() throws MiniDbException {
        MemoryCatalog catalog = catalogWithUsers();

        MiniDbException e = assertThrows(MiniDbException.class,
            () -> catalog.getType("orders", "id"));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("orders"));
    }

    @Test
    void findMissingReturnsEmptyWithoutThrowing() throws MiniDbException {
        MemoryCatalog catalog = catalogWithUsers();

        assertTrue(catalog.findTable("orders").isEmpty());
        assertTrue(catalog.findColumn("orders", "id").isEmpty());
        assertTrue(catalog.findColumn("users", "age").isEmpty());
    }

    @Test
    void varcharMaxLengthReadable() throws MiniDbException {
        MemoryCatalog catalog = catalogWithUsers();

        assertEquals(32, catalog.findColumn("users", "name").get().maxLength());
    }

    @Test
    void identifiersAreCaseInsensitive() throws MiniDbException {
        MemoryCatalog catalog = new MemoryCatalog();
        catalog.createTable(new TableDef("Student", List.of(
            new ColumnDef("ID", DataType.INT, 0),
            new ColumnDef("Name", DataType.VARCHAR, 16))));

        assertTrue(catalog.findTable("student").isPresent());
        assertTrue(catalog.findTable("STUDENT").isPresent());
        assertEquals(DataType.INT, catalog.getType("STUDENT", "id"));
        assertTrue(catalog.findColumn("student", "NAME").isPresent());
    }

    @Test
    void duplicateCreateWithDifferentCaseThrows() throws MiniDbException {
        MemoryCatalog catalog = catalogWithUsers();

        MiniDbException e = assertThrows(MiniDbException.class,
            () -> catalog.createTable(new TableDef("USERS", List.of(
                new ColumnDef("id", DataType.INT, 0)))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
    }
}
