package com.gbujak.kanalarz.teststeps;

public class TestUser {

    private String name;

    public TestUser(String name) {
        this.name = name;
    }

    String name() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }
}
