package com.gbujak.kanalarz.step;

public record Step<Produces>(
    Class<Produces> producesClass
) { }
