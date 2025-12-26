package com.redmoon2333.records;

import lombok.Data;

import java.util.Objects;


@Data
public class Book {
    int id;
    String bookName;

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Book book = (Book) o;
        return id == book.id && Objects.equals(bookName, book.bookName);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(id, bookName);
    }

    @Override
    public String toString()
    {
        return "Book{" +
                "id=" + id +
                ", bookName='" + bookName + '\'' +
                '}';
    }
}
