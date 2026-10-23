/*------------------------------------------------------------------------------
This source is part of the Oracle FLEXCUBE V.CO 4.5.0.0.0.0.0 Software System
and is copyrighted by Oracle Financial Services Software Limited.
All rights reserved.  No part of this work may be reproduced,
stored in a retrieval system, adopted or transmitted in any form or
by any means,electronic, mechanical, photographic, graphic,
optic recording or otherwise,translated in any language or
computer language, without the prior written permission of
Oracle Financial Services Software Limited.
Oracle Financial Services Software Limited.
10-11, SDF I, SEEPZ, Andheri (East),
Mumbai - 400 096.
India
Copyright c [2010] Oracle Financial Services Software Limited.
      $Revision$
      $LastChangedDate$
      $LastChangedBy$
      $HeadURL$
      $Id$
-------------------------------------------------------------------------------
Modification History
Date        Version     Author          Description
----------  ----------- --------------- ---------------------------------------
14/09/2026  1.0         pmraut          PO01 NEFT Payment Discovery Inquiry API
-------------------------------------------------------------------------------*/

package com.ofss.fc.xface.db.procedure.impl;

import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Iterator;

import com.ofss.fc.db.procedure.AbstractProcedure;
import com.ofss.fc.infra.exception.FatalException;

/**
 * Procedure adapter for PK_BA_NEFT_EVIDENCE_INQ.AP_BA_NEFT_DISCOVERY_INQ.
 */
public class PkBaNEFTDiscoveryInquiry extends AbstractProcedure {

    public static final String PROC_AP_BA_NEFT_DISCOVERY_INQ =
            "ApBaNeftDiscoveryInq";

    public static final String PARAM_PIO_ORG_BRN = "PioOrgBrn";
    public static final String PARAM_PIO_ORG_BANK = "PioOrgBank";
    public static final String PARAM_PI_REFERENCE_TYPE = "PiReferenceType";
    public static final String PARAM_PI_REFERENCE = "PiReference";
    public static final String PARAM_PI_INQUIRY_DATE = "PiInquiryDate";
    public static final String PARAM_PI_RECORD_COUNT = "PiRecordCount";

    public PkBaNEFTDiscoveryInquiry(Connection connection) {
        super(connection);
    }

    public PkBaNEFTDiscoveryInquiry() throws FatalException {
        super();
    }

    protected void registerOutParams(String procName) throws SQLException {
        if (procName.equalsIgnoreCase(PROC_AP_BA_NEFT_DISCOVERY_INQ)) {
            setOutputParam(1, Types.BIGINT);
            setOutputParam(2, Types.NUMERIC);
            setOutputParam(3, Types.NUMERIC);
            setOutputParam(4, oracle.jdbc.OracleTypes.CURSOR);
            setOutputParam(5, Types.VARCHAR);
        }
    }

    private String getStmntForApBaNeftDiscoveryInq() {
        return "DECLARE\n"
                + "BEGIN\n"
                + "?:= PK_BA_NEFT_EVIDENCE_INQ.AP_BA_NEFT_DISCOVERY_INQ(?,?,?,?,?,?,?,?) ; \n"
                + "END;";
    }

    public ReturnVOApBaNeftDiscoveryInq execApBaNeftDiscoveryInq(
            Integer originatingBranchCode, Integer originatingBankCode,
            String referenceType, String reference, Date inquiryDate,
            Integer recordCount) throws SQLException {

        String procStmt = getStmntForApBaNeftDiscoveryInq();
        try {
            debugProcedureStart(PROC_AP_BA_NEFT_DISCOVERY_INQ);
            prepareCall(procStmt);
            setInputParameters(originatingBranchCode, originatingBankCode,
                    referenceType, reference, inquiryDate, recordCount);
            registerOutParams(PROC_AP_BA_NEFT_DISCOVERY_INQ);
            execute();
            debugProcedureEnd(PROC_AP_BA_NEFT_DISCOVERY_INQ);
            return new ReturnVOApBaNeftDiscoveryInq(this);
        } catch (SQLException sqlException) {
            debugProcedureFailed(PROC_AP_BA_NEFT_DISCOVERY_INQ,
                    sqlException);
            throw sqlException;
        } finally {
            releaseResources(procStmt);
        }
    }

    private void setInputParameters(Integer originatingBranchCode,
            Integer originatingBankCode, String referenceType,
            String reference, Date inquiryDate, Integer recordCount)
            throws SQLException {

        setParameter(getParamKey(PROC_AP_BA_NEFT_DISCOVERY_INQ,
                PARAM_PIO_ORG_BRN), originatingBranchCode, 2);
        debugParameter(PARAM_PIO_ORG_BRN, originatingBranchCode);

        setParameter(getParamKey(PROC_AP_BA_NEFT_DISCOVERY_INQ,
                PARAM_PIO_ORG_BANK), originatingBankCode, 3);
        debugParameter(PARAM_PIO_ORG_BANK, originatingBankCode);

        setParameter(getParamKey(PROC_AP_BA_NEFT_DISCOVERY_INQ,
                PARAM_PI_REFERENCE_TYPE), referenceType, 6);
        debugParameter(PARAM_PI_REFERENCE_TYPE, referenceType);

        setParameter(getParamKey(PROC_AP_BA_NEFT_DISCOVERY_INQ,
                PARAM_PI_REFERENCE), reference, 7);
        debugParameter(PARAM_PI_REFERENCE, reference);

        setParameter(getParamKey(PROC_AP_BA_NEFT_DISCOVERY_INQ,
                PARAM_PI_INQUIRY_DATE), inquiryDate, 8);
        debugParameter(PARAM_PI_INQUIRY_DATE, inquiryDate);

        if (recordCount == null) {
            statement.setNull(9, Types.NUMERIC);
        } else {
            setParameter(getParamKey(PROC_AP_BA_NEFT_DISCOVERY_INQ,
                    PARAM_PI_RECORD_COUNT), recordCount.intValue(), 9);
        }
        debugParameter(PARAM_PI_RECORD_COUNT,
                recordCount == null ? null : recordCount.toString());
    }

    public class CursorApBaNeftDiscoveryInq_1 {

        private String refTxnNo;
        private String orgBrn;
        private String orgBank;
        private String refSubseqNo;
        private String utrRefNo;
        private String datInitiation;
        private String numAmount4038;

        public String getRefTxnNo() {
            return refTxnNo;
        }

        public void setRefTxnNo(String refTxnNo) {
            this.refTxnNo = refTxnNo;
        }

        public String getOrgBrn() {
            return orgBrn;
        }

        public void setOrgBrn(String orgBrn) {
            this.orgBrn = orgBrn;
        }

        public String getOrgBank() {
            return orgBank;
        }

        public void setOrgBank(String orgBank) {
            this.orgBank = orgBank;
        }

        public String getRefSubseqNo() {
            return refSubseqNo;
        }

        public void setRefSubseqNo(String refSubseqNo) {
            this.refSubseqNo = refSubseqNo;
        }

        public String getUtrRefNo() {
            return utrRefNo;
        }

        public void setUtrRefNo(String utrRefNo) {
            this.utrRefNo = utrRefNo;
        }

        public String getDatInitiation() {
            return datInitiation;
        }

        public void setDatInitiation(String datInitiation) {
            this.datInitiation = datInitiation;
        }

        public String getNumAmount4038() {
            return numAmount4038;
        }

        public void setNumAmount4038(String numAmount4038) {
            this.numAmount4038 = numAmount4038;
        }
    }

    private ArrayList<CursorApBaNeftDiscoveryInq_1>
            cursorApBaNeftDiscoveryInq_1 =
                    new ArrayList<CursorApBaNeftDiscoveryInq_1>();

    private void fetchCursorApBaNeftDiscoveryInq_1() throws SQLException {
        ResultSet resultSet = null;
        try {
            resultSet = getCursor(4);
            cursorApBaNeftDiscoveryInq_1.clear();
            if (resultSet == null) {
                return;
            }

            while (resultSet.next()) {
                CursorApBaNeftDiscoveryInq_1 cursorObj =
                        new CursorApBaNeftDiscoveryInq_1();
                cursorObj.setRefTxnNo(resultSet.getString(1));
                cursorObj.setOrgBrn(resultSet.getString(2));
                cursorObj.setOrgBank(resultSet.getString(3));
                cursorObj.setRefSubseqNo(resultSet.getString(4));
                cursorObj.setUtrRefNo(resultSet.getString(5));
                cursorObj.setDatInitiation(resultSet.getString(6));
                cursorObj.setNumAmount4038(resultSet.getString(7));
                cursorApBaNeftDiscoveryInq_1.add(cursorObj);
            }
        } finally {
            if (resultSet != null) {
                resultSet.close();
            }
        }
    }

    public class ReturnVOApBaNeftDiscoveryInq {

        private long returnValue;
        private Integer originatingBranchCode;
        private Integer originatingBankCode;
        private String observedAt;

        public long getReturnValue() {
            return returnValue;
        }

        public void setReturnValue(long returnValue) {
            this.returnValue = returnValue;
        }

        public Integer getOriginatingBranchCode() {
            return originatingBranchCode;
        }

        public void setOriginatingBranchCode(Integer originatingBranchCode) {
            this.originatingBranchCode = originatingBranchCode;
        }

        public Integer getOriginatingBankCode() {
            return originatingBankCode;
        }

        public void setOriginatingBankCode(Integer originatingBankCode) {
            this.originatingBankCode = originatingBankCode;
        }

        public String getObservedAt() {
            return observedAt;
        }

        public void setObservedAt(String observedAt) {
            this.observedAt = observedAt;
        }

        public CursorApBaNeftDiscoveryInq_1[] getCursor1() {
            CursorApBaNeftDiscoveryInq_1[] cursor =
                    new CursorApBaNeftDiscoveryInq_1[
                            cursorApBaNeftDiscoveryInq_1.size()];
            Iterator<CursorApBaNeftDiscoveryInq_1> iterator =
                    cursorApBaNeftDiscoveryInq_1.iterator();
            int index = 0;
            while (iterator.hasNext()) {
                cursor[index++] = iterator.next();
            }
            return cursor;
        }

        ReturnVOApBaNeftDiscoveryInq(PkBaNEFTDiscoveryInquiry procedure)
                throws SQLException {
            setReturnValue(procedure.getLong(1));
            setOriginatingBranchCode(
                    Integer.valueOf((int) procedure.getLong(2)));
            setOriginatingBankCode(
                    Integer.valueOf((int) procedure.getLong(3)));
            setObservedAt(procedure.getString(5));
            if (getReturnValue() == 0) {
                procedure.fetchCursorApBaNeftDiscoveryInq_1();
            }
        }
    }
}
