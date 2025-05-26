package com.example.model.PBFT;

import lombok.Data;

import java.util.List;

//<VIEW-CHANGE, v+1, n, C, P, i>
@Data
public class NewView {
    private String agg_signature;
    private List<String> nodes;
    private List<BaseMessage> viewChanges;
}
