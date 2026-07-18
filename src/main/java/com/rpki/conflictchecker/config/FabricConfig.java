package com.rpki.conflictchecker.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FabricConfig {
    
    private Gateway gateway;
    private User user;
    private Chaincode chaincode;
    
    @Getter
    @Setter
    public static class Gateway {
        private String connectionProfile;
        private int tlsEnabled;
    }
    
    @Getter
    @Setter
    public static class User {
        private String name;
        private String mspId;
        private String certPath;
        private String keyPath;
    }
    
    @Getter
    @Setter
    public static class Chaincode {
        private String name;
        private String channel;
        private String version;
    }
}
