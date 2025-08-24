package com.gbujak.kanalarz.teststeps;

import com.fasterxml.jackson.annotation.JsonValue;

public class TestUser {

    @JsonValue
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
