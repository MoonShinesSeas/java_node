package com.example.model.client;

import lombok.Data;

// <REPLY, v, t, c, i, r>
@Data
public class Reply {
    private int type;
    private int view;
    private long timestamp;
    private String nodeIndex;
    private String res;
}
