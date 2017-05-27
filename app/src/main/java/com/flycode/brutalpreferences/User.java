package com.flycode.brutalpreferences;

import com.flycode.Brutal;
import com.flycode.BrutalPreferences;

/**
 * Created by acerkinght on 5/27/17.
 */

@BrutalPreferences("userPreferences")
public abstract class User {
    @Brutal("name")
    private String name;
    @Brutal("id")
    private int id;
}
