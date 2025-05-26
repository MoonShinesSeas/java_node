package com.example.util;

import it.unisa.dia.gas.jpbc.Pairing;
import it.unisa.dia.gas.plaf.jpbc.pairing.PairingFactory;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;

public class Common {
    public static Pairing pairing = PairingFactory.getPairing("a.properties");
    public final static String g1_String = "LzsPlRBJW971TyZZoIOOojjPZMI2IfonqgV1GD8mVCqScD0cC0MTMSNimm4gWhcmomGZk2qwWwr2uJqD7U/GCpGT/9uP3DzBW0A4X/bb2KFaH/7li5UNFxM5jx0P91fNwoKEi9uQkM3TfaspNatF22eDzAO0XSR1llMDIjWREGI=";
    public final static ASN1ObjectIdentifier oid = new ASN1ObjectIdentifier("1.2.156.10197.1.301.1");
}
