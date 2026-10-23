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
----------  -----------  --------------  ---------------------------------------
13/09/2026  1.0          pmraut          PO02 NEFT Evidence Inquiry API
-------------------------------------------------------------------------------*/

package com.ofss.fc.xface.db.procedure.impl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Iterator;

import com.ofss.fc.db.procedure.AbstractProcedure;
import com.ofss.fc.infra.exception.FatalException;

/**
 * Procedure adapter for the four evidence inquiry functions exposed by
 * PK_BA_NEFT_EVIDENCE_INQ.
 */
public class PkBaNEFTEvidenceInquiry extends AbstractProcedure {

    public static final String PROC_AP_BA_NEFT_PAYMENT_INQ =
            "ApBaNeftPaymentInq";
    public static final String PROC_AP_BA_NEFT_HOST_INQ =
            "ApBaNeftHostInq";
    public static final String PROC_AP_BA_NEFT_HISTORY_INQ =
            "ApBaNeftHistoryInq";
    public static final String PROC_AP_BA_NEFT_STATUS_INQ =
            "ApBaNeftStatusInq";

    public static final String PARAM_PIO_REF_TXN_NO = "PioRefTxnNo";
    public static final String PARAM_PIO_ORG_BRN = "PioOrgBrn";
    public static final String PARAM_PIO_ORG_BANK = "PioOrgBank";

    public PkBaNEFTEvidenceInquiry(Connection connection) {
        super(connection);
    }

    public PkBaNEFTEvidenceInquiry() throws FatalException {
        super();
    }

    protected void registerOutParams(String procName) throws SQLException {
        if (procName.equalsIgnoreCase(PROC_AP_BA_NEFT_PAYMENT_INQ)
                || procName.equalsIgnoreCase(PROC_AP_BA_NEFT_HOST_INQ)
                || procName.equalsIgnoreCase(PROC_AP_BA_NEFT_HISTORY_INQ)
                || procName.equalsIgnoreCase(PROC_AP_BA_NEFT_STATUS_INQ)) {
            setOutputParam(1, Types.BIGINT);
            setOutputParam(2, Types.VARCHAR);
            setOutputParam(3, Types.NUMERIC);
            setOutputParam(4, Types.NUMERIC);
            setOutputParam(5, oracle.jdbc.OracleTypes.CURSOR);
        }
    }

    private String getStmntForApBaNeftPaymentInq() {
        return "DECLARE\n"
                + "BEGIN\n"
                + "?:= PK_BA_NEFT_EVIDENCE_INQ.AP_BA_NEFT_PAYMENT_INQ(?,?,?,?) ; \n"
                + "END;";
    }

    private String getStmntForApBaNeftHostInq() {
        return "DECLARE\n"
                + "BEGIN\n"
                + "?:= PK_BA_NEFT_EVIDENCE_INQ.AP_BA_NEFT_HOST_INQ(?,?,?,?) ; \n"
                + "END;";
    }

    private String getStmntForApBaNeftHistoryInq() {
        return "DECLARE\n"
                + "BEGIN\n"
                + "?:= PK_BA_NEFT_EVIDENCE_INQ.AP_BA_NEFT_HISTORY_INQ(?,?,?,?) ; \n"
                + "END;";
    }

    private String getStmntForApBaNeftStatusInq() {
        return "DECLARE\n"
                + "BEGIN\n"
                + "?:= PK_BA_NEFT_EVIDENCE_INQ.AP_BA_NEFT_STATUS_INQ(?,?,?,?) ; \n"
                + "END;";
    }

    public ReturnVOApBaNeftPaymentInq execApBaNeftPaymentInq(
            String referenceTransactionNumber, Integer originatingBranchCode,
            Integer originatingBankCode) throws SQLException {

        String procStmt = getStmntForApBaNeftPaymentInq();
        try {
            debugProcedureStart(PROC_AP_BA_NEFT_PAYMENT_INQ);
            prepareCall(procStmt);
            setCommonInputParameters(PROC_AP_BA_NEFT_PAYMENT_INQ,
                    referenceTransactionNumber, originatingBranchCode,
                    originatingBankCode);
            registerOutParams(PROC_AP_BA_NEFT_PAYMENT_INQ);
            execute();
            debugProcedureEnd(PROC_AP_BA_NEFT_PAYMENT_INQ);
            return new ReturnVOApBaNeftPaymentInq(this);
        } catch (SQLException sqlException) {
            debugProcedureFailed(PROC_AP_BA_NEFT_PAYMENT_INQ, sqlException);
            throw sqlException;
        } finally {
            releaseResources(procStmt);
        }
    }

    public ReturnVOApBaNeftHostInq execApBaNeftHostInq(
            String referenceTransactionNumber, Integer originatingBranchCode,
            Integer originatingBankCode) throws SQLException {

        String procStmt = getStmntForApBaNeftHostInq();
        try {
            debugProcedureStart(PROC_AP_BA_NEFT_HOST_INQ);
            prepareCall(procStmt);
            setCommonInputParameters(PROC_AP_BA_NEFT_HOST_INQ,
                    referenceTransactionNumber, originatingBranchCode,
                    originatingBankCode);
            registerOutParams(PROC_AP_BA_NEFT_HOST_INQ);
            execute();
            debugProcedureEnd(PROC_AP_BA_NEFT_HOST_INQ);
            return new ReturnVOApBaNeftHostInq(this);
        } catch (SQLException sqlException) {
            debugProcedureFailed(PROC_AP_BA_NEFT_HOST_INQ, sqlException);
            throw sqlException;
        } finally {
            releaseResources(procStmt);
        }
    }

    public ReturnVOApBaNeftHistoryInq execApBaNeftHistoryInq(
            String referenceTransactionNumber, Integer originatingBranchCode,
            Integer originatingBankCode) throws SQLException {

        String procStmt = getStmntForApBaNeftHistoryInq();
        try {
            debugProcedureStart(PROC_AP_BA_NEFT_HISTORY_INQ);
            prepareCall(procStmt);
            setCommonInputParameters(PROC_AP_BA_NEFT_HISTORY_INQ,
                    referenceTransactionNumber, originatingBranchCode,
                    originatingBankCode);
            registerOutParams(PROC_AP_BA_NEFT_HISTORY_INQ);
            execute();
            debugProcedureEnd(PROC_AP_BA_NEFT_HISTORY_INQ);
            return new ReturnVOApBaNeftHistoryInq(this);
        } catch (SQLException sqlException) {
            debugProcedureFailed(PROC_AP_BA_NEFT_HISTORY_INQ, sqlException);
            throw sqlException;
        } finally {
            releaseResources(procStmt);
        }
    }

    public ReturnVOApBaNeftStatusInq execApBaNeftStatusInq(
            String referenceTransactionNumber, Integer originatingBranchCode,
            Integer originatingBankCode) throws SQLException {

        String procStmt = getStmntForApBaNeftStatusInq();
        try {
            debugProcedureStart(PROC_AP_BA_NEFT_STATUS_INQ);
            prepareCall(procStmt);
            setCommonInputParameters(PROC_AP_BA_NEFT_STATUS_INQ,
                    referenceTransactionNumber, originatingBranchCode,
                    originatingBankCode);
            registerOutParams(PROC_AP_BA_NEFT_STATUS_INQ);
            execute();
            debugProcedureEnd(PROC_AP_BA_NEFT_STATUS_INQ);
            return new ReturnVOApBaNeftStatusInq(this);
        } catch (SQLException sqlException) {
            debugProcedureFailed(PROC_AP_BA_NEFT_STATUS_INQ, sqlException);
            throw sqlException;
        } finally {
            releaseResources(procStmt);
        }
    }

    private void setCommonInputParameters(String procName,
            String referenceTransactionNumber, Integer originatingBranchCode,
            Integer originatingBankCode) throws SQLException {

        setParameter(getParamKey(procName, PARAM_PIO_REF_TXN_NO),
                referenceTransactionNumber, 2);
        debugParameter(PARAM_PIO_REF_TXN_NO, referenceTransactionNumber);

        setParameter(getParamKey(procName, PARAM_PIO_ORG_BRN),
                originatingBranchCode, 3);
        debugParameter(PARAM_PIO_ORG_BRN, originatingBranchCode);

        setParameter(getParamKey(procName, PARAM_PIO_ORG_BANK),
                originatingBankCode, 4);
        debugParameter(PARAM_PIO_ORG_BANK, originatingBankCode);
    }

    public class CursorApBaNeftPaymentInq_1 {

        private String sourceTable;
        private String queryObservedAt;
        private String scopeRowCount;
        private String refTxnNumber;
        private String idUserReference2020;
        private String idRelatedRef2006;
        private String utrRefNo;
        private String idMsgReference2020;
        private String idSequenceNo;
        private String numAmount4038;
        private String codCurr;
        private String direction;
        private String datInitiation;
        private String datValue3380;
        private String msgType;
        private String subMsgType;
        private String txnType;
        private String codStatus;
        private String acctStatus;
        private String msgStatus;
        private String numRetry;
        private String batchTime3535;
        private String reasonCode6346;
        private String n10MsgId;
        private String n10IdSequenceNo;
        private String n10TxnId;
        private String n10Status;
        private String n10DateTime;
        private String n10RecvSentDateTime;

        public String getSourceTable() {
            return sourceTable;
        }

        public void setSourceTable(String sourceTable) {
            this.sourceTable = sourceTable;
        }

        public String getQueryObservedAt() {
            return queryObservedAt;
        }

        public void setQueryObservedAt(String queryObservedAt) {
            this.queryObservedAt = queryObservedAt;
        }

        public String getScopeRowCount() {
            return scopeRowCount;
        }

        public void setScopeRowCount(String scopeRowCount) {
            this.scopeRowCount = scopeRowCount;
        }

        public String getRefTxnNumber() {
            return refTxnNumber;
        }

        public void setRefTxnNumber(String refTxnNumber) {
            this.refTxnNumber = refTxnNumber;
        }

        public String getIdUserReference2020() {
            return idUserReference2020;
        }

        public void setIdUserReference2020(String idUserReference2020) {
            this.idUserReference2020 = idUserReference2020;
        }

        public String getIdRelatedRef2006() {
            return idRelatedRef2006;
        }

        public void setIdRelatedRef2006(String idRelatedRef2006) {
            this.idRelatedRef2006 = idRelatedRef2006;
        }

        public String getUtrRefNo() {
            return utrRefNo;
        }

        public void setUtrRefNo(String utrRefNo) {
            this.utrRefNo = utrRefNo;
        }

        public String getIdMsgReference2020() {
            return idMsgReference2020;
        }

        public void setIdMsgReference2020(String idMsgReference2020) {
            this.idMsgReference2020 = idMsgReference2020;
        }

        public String getIdSequenceNo() {
            return idSequenceNo;
        }

        public void setIdSequenceNo(String idSequenceNo) {
            this.idSequenceNo = idSequenceNo;
        }

        public String getNumAmount4038() {
            return numAmount4038;
        }

        public void setNumAmount4038(String numAmount4038) {
            this.numAmount4038 = numAmount4038;
        }

        public String getCodCurr() {
            return codCurr;
        }

        public void setCodCurr(String codCurr) {
            this.codCurr = codCurr;
        }

        public String getDirection() {
            return direction;
        }

        public void setDirection(String direction) {
            this.direction = direction;
        }

        public String getDatInitiation() {
            return datInitiation;
        }

        public void setDatInitiation(String datInitiation) {
            this.datInitiation = datInitiation;
        }

        public String getDatValue3380() {
            return datValue3380;
        }

        public void setDatValue3380(String datValue3380) {
            this.datValue3380 = datValue3380;
        }

        public String getMsgType() {
            return msgType;
        }

        public void setMsgType(String msgType) {
            this.msgType = msgType;
        }

        public String getSubMsgType() {
            return subMsgType;
        }

        public void setSubMsgType(String subMsgType) {
            this.subMsgType = subMsgType;
        }

        public String getTxnType() {
            return txnType;
        }

        public void setTxnType(String txnType) {
            this.txnType = txnType;
        }

        public String getCodStatus() {
            return codStatus;
        }

        public void setCodStatus(String codStatus) {
            this.codStatus = codStatus;
        }

        public String getAcctStatus() {
            return acctStatus;
        }

        public void setAcctStatus(String acctStatus) {
            this.acctStatus = acctStatus;
        }

        public String getMsgStatus() {
            return msgStatus;
        }

        public void setMsgStatus(String msgStatus) {
            this.msgStatus = msgStatus;
        }

        public String getNumRetry() {
            return numRetry;
        }

        public void setNumRetry(String numRetry) {
            this.numRetry = numRetry;
        }

        public String getBatchTime3535() {
            return batchTime3535;
        }

        public void setBatchTime3535(String batchTime3535) {
            this.batchTime3535 = batchTime3535;
        }

        public String getReasonCode6346() {
            return reasonCode6346;
        }

        public void setReasonCode6346(String reasonCode6346) {
            this.reasonCode6346 = reasonCode6346;
        }

        public String getN10MsgId() {
            return n10MsgId;
        }

        public void setN10MsgId(String n10MsgId) {
            this.n10MsgId = n10MsgId;
        }

        public String getN10IdSequenceNo() {
            return n10IdSequenceNo;
        }

        public void setN10IdSequenceNo(String n10IdSequenceNo) {
            this.n10IdSequenceNo = n10IdSequenceNo;
        }

        public String getN10TxnId() {
            return n10TxnId;
        }

        public void setN10TxnId(String n10TxnId) {
            this.n10TxnId = n10TxnId;
        }

        public String getN10Status() {
            return n10Status;
        }

        public void setN10Status(String n10Status) {
            this.n10Status = n10Status;
        }

        public String getN10DateTime() {
            return n10DateTime;
        }

        public void setN10DateTime(String n10DateTime) {
            this.n10DateTime = n10DateTime;
        }

        public String getN10RecvSentDateTime() {
            return n10RecvSentDateTime;
        }

        public void setN10RecvSentDateTime(String n10RecvSentDateTime) {
            this.n10RecvSentDateTime = n10RecvSentDateTime;
        }
    }

    private ArrayList<CursorApBaNeftPaymentInq_1>
            cursorApBaNeftPaymentInq_1 =
                    new ArrayList<CursorApBaNeftPaymentInq_1>();

    private void fetchCursorApBaNeftPaymentInq_1() throws SQLException {
        ResultSet resultSet = null;
        try {
            resultSet = getCursor(5);
            cursorApBaNeftPaymentInq_1.clear();
            if (resultSet == null) {
                return;
            }

            while (resultSet.next()) {
                CursorApBaNeftPaymentInq_1 cursorObj =
                        new CursorApBaNeftPaymentInq_1();
                cursorObj.setSourceTable(resultSet.getString(1));
                cursorObj.setQueryObservedAt(resultSet.getString(2));
                cursorObj.setScopeRowCount(resultSet.getString(3));
                cursorObj.setRefTxnNumber(resultSet.getString(4));
                cursorObj.setIdUserReference2020(resultSet.getString(5));
                cursorObj.setIdRelatedRef2006(resultSet.getString(6));
                cursorObj.setUtrRefNo(resultSet.getString(7));
                cursorObj.setIdMsgReference2020(resultSet.getString(8));
                cursorObj.setIdSequenceNo(resultSet.getString(9));
                cursorObj.setNumAmount4038(resultSet.getString(10));
                cursorObj.setCodCurr(resultSet.getString(11));
                cursorObj.setDirection(resultSet.getString(12));
                cursorObj.setDatInitiation(resultSet.getString(13));
                cursorObj.setDatValue3380(resultSet.getString(14));
                cursorObj.setMsgType(resultSet.getString(15));
                cursorObj.setSubMsgType(resultSet.getString(16));
                cursorObj.setTxnType(resultSet.getString(17));
                cursorObj.setCodStatus(resultSet.getString(18));
                cursorObj.setAcctStatus(resultSet.getString(19));
                cursorObj.setMsgStatus(resultSet.getString(20));
                cursorObj.setNumRetry(resultSet.getString(21));
                cursorObj.setBatchTime3535(resultSet.getString(22));
                cursorObj.setReasonCode6346(resultSet.getString(23));
                cursorObj.setN10MsgId(resultSet.getString(24));
                cursorObj.setN10IdSequenceNo(resultSet.getString(25));
                cursorObj.setN10TxnId(resultSet.getString(26));
                cursorObj.setN10Status(resultSet.getString(27));
                cursorObj.setN10DateTime(resultSet.getString(28));
                cursorObj.setN10RecvSentDateTime(resultSet.getString(29));
                cursorApBaNeftPaymentInq_1.add(cursorObj);
            }
        } finally {
            if (resultSet != null) {
                resultSet.close();
            }
        }
    }

    public class ReturnVOApBaNeftPaymentInq {

        private long returnValue;
        private String refTxnNumber;
        private Integer originatingBranchCode;
        private Integer originatingBankCode;

        public long getReturnValue() {
            return returnValue;
        }

        public void setReturnValue(long returnValue) {
            this.returnValue = returnValue;
        }

        public String getRefTxnNumber() {
            return refTxnNumber;
        }

        public void setRefTxnNumber(String refTxnNumber) {
            this.refTxnNumber = refTxnNumber;
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

        public CursorApBaNeftPaymentInq_1[] getCursor1() {
            CursorApBaNeftPaymentInq_1[] cursor =
                    new CursorApBaNeftPaymentInq_1[
                            cursorApBaNeftPaymentInq_1.size()];
            Iterator<CursorApBaNeftPaymentInq_1> iterator =
                    cursorApBaNeftPaymentInq_1.iterator();
            int index = 0;
            while (iterator.hasNext()) {
                cursor[index++] = iterator.next();
            }
            return cursor;
        }

        ReturnVOApBaNeftPaymentInq(PkBaNEFTEvidenceInquiry procedure)
                throws SQLException {
            setReturnValue(procedure.getLong(1));
            setRefTxnNumber(procedure.getString(2));
            setOriginatingBranchCode(
                    Integer.valueOf((int) procedure.getLong(3)));
            setOriginatingBankCode(
                    Integer.valueOf((int) procedure.getLong(4)));
            if (getReturnValue() == 0) {
                procedure.fetchCursorApBaNeftPaymentInq_1();
            }
        }
    }

    public class CursorApBaNeftHostInq_1 {

        private String sourceTable;
        private String queryObservedAt;
        private String scopeRowCount;
        private String refTxnNo;
        private String refSubseqNo;
        private String refNetworkNo;
        private String refUsrNo;
        private String codOrgBrn;
        private String codOrgBank;
        private String codNetworkId;
        private String codPaymentTxn;
        private String payDir;
        private String codChnlId;
        private String amtTxnTcy;
        private String codTxnCcy;
        private String datTxn;
        private String datInitiation;
        private String datPost;
        private String datValue;
        private String datActivation;
        private String datDispatch;
        private String datNtwkValue;
        private String datAuthtime;
        private String datSecondAuthtime;
        private String txnStat;
        private String acctStat;
        private String msgStat;
        private String ntwkAcctStat;
        private String contgAcctStat;
        private String ctPartyAcctStat;
        private String flgPostCutoff;
        private String codReply;
        private String codExt;
        private String codReject;
        private String sfmsRejCode;
        private String refTxnNoRev;

        public String getSourceTable() {
            return sourceTable;
        }

        public void setSourceTable(String sourceTable) {
            this.sourceTable = sourceTable;
        }

        public String getQueryObservedAt() {
            return queryObservedAt;
        }

        public void setQueryObservedAt(String queryObservedAt) {
            this.queryObservedAt = queryObservedAt;
        }

        public String getScopeRowCount() {
            return scopeRowCount;
        }

        public void setScopeRowCount(String scopeRowCount) {
            this.scopeRowCount = scopeRowCount;
        }

        public String getRefTxnNo() {
            return refTxnNo;
        }

        public void setRefTxnNo(String refTxnNo) {
            this.refTxnNo = refTxnNo;
        }

        public String getRefSubseqNo() {
            return refSubseqNo;
        }

        public void setRefSubseqNo(String refSubseqNo) {
            this.refSubseqNo = refSubseqNo;
        }

        public String getRefNetworkNo() {
            return refNetworkNo;
        }

        public void setRefNetworkNo(String refNetworkNo) {
            this.refNetworkNo = refNetworkNo;
        }

        public String getRefUsrNo() {
            return refUsrNo;
        }

        public void setRefUsrNo(String refUsrNo) {
            this.refUsrNo = refUsrNo;
        }

        public String getCodOrgBrn() {
            return codOrgBrn;
        }

        public void setCodOrgBrn(String codOrgBrn) {
            this.codOrgBrn = codOrgBrn;
        }

        public String getCodOrgBank() {
            return codOrgBank;
        }

        public void setCodOrgBank(String codOrgBank) {
            this.codOrgBank = codOrgBank;
        }

        public String getCodNetworkId() {
            return codNetworkId;
        }

        public void setCodNetworkId(String codNetworkId) {
            this.codNetworkId = codNetworkId;
        }

        public String getCodPaymentTxn() {
            return codPaymentTxn;
        }

        public void setCodPaymentTxn(String codPaymentTxn) {
            this.codPaymentTxn = codPaymentTxn;
        }

        public String getPayDir() {
            return payDir;
        }

        public void setPayDir(String payDir) {
            this.payDir = payDir;
        }

        public String getCodChnlId() {
            return codChnlId;
        }

        public void setCodChnlId(String codChnlId) {
            this.codChnlId = codChnlId;
        }

        public String getAmtTxnTcy() {
            return amtTxnTcy;
        }

        public void setAmtTxnTcy(String amtTxnTcy) {
            this.amtTxnTcy = amtTxnTcy;
        }

        public String getCodTxnCcy() {
            return codTxnCcy;
        }

        public void setCodTxnCcy(String codTxnCcy) {
            this.codTxnCcy = codTxnCcy;
        }

        public String getDatTxn() {
            return datTxn;
        }

        public void setDatTxn(String datTxn) {
            this.datTxn = datTxn;
        }

        public String getDatInitiation() {
            return datInitiation;
        }

        public void setDatInitiation(String datInitiation) {
            this.datInitiation = datInitiation;
        }

        public String getDatPost() {
            return datPost;
        }

        public void setDatPost(String datPost) {
            this.datPost = datPost;
        }

        public String getDatValue() {
            return datValue;
        }

        public void setDatValue(String datValue) {
            this.datValue = datValue;
        }

        public String getDatActivation() {
            return datActivation;
        }

        public void setDatActivation(String datActivation) {
            this.datActivation = datActivation;
        }

        public String getDatDispatch() {
            return datDispatch;
        }

        public void setDatDispatch(String datDispatch) {
            this.datDispatch = datDispatch;
        }

        public String getDatNtwkValue() {
            return datNtwkValue;
        }

        public void setDatNtwkValue(String datNtwkValue) {
            this.datNtwkValue = datNtwkValue;
        }

        public String getDatAuthtime() {
            return datAuthtime;
        }

        public void setDatAuthtime(String datAuthtime) {
            this.datAuthtime = datAuthtime;
        }

        public String getDatSecondAuthtime() {
            return datSecondAuthtime;
        }

        public void setDatSecondAuthtime(String datSecondAuthtime) {
            this.datSecondAuthtime = datSecondAuthtime;
        }

        public String getTxnStat() {
            return txnStat;
        }

        public void setTxnStat(String txnStat) {
            this.txnStat = txnStat;
        }

        public String getAcctStat() {
            return acctStat;
        }

        public void setAcctStat(String acctStat) {
            this.acctStat = acctStat;
        }

        public String getMsgStat() {
            return msgStat;
        }

        public void setMsgStat(String msgStat) {
            this.msgStat = msgStat;
        }

        public String getNtwkAcctStat() {
            return ntwkAcctStat;
        }

        public void setNtwkAcctStat(String ntwkAcctStat) {
            this.ntwkAcctStat = ntwkAcctStat;
        }

        public String getContgAcctStat() {
            return contgAcctStat;
        }

        public void setContgAcctStat(String contgAcctStat) {
            this.contgAcctStat = contgAcctStat;
        }

        public String getCtPartyAcctStat() {
            return ctPartyAcctStat;
        }

        public void setCtPartyAcctStat(String ctPartyAcctStat) {
            this.ctPartyAcctStat = ctPartyAcctStat;
        }

        public String getFlgPostCutoff() {
            return flgPostCutoff;
        }

        public void setFlgPostCutoff(String flgPostCutoff) {
            this.flgPostCutoff = flgPostCutoff;
        }

        public String getCodReply() {
            return codReply;
        }

        public void setCodReply(String codReply) {
            this.codReply = codReply;
        }

        public String getCodExt() {
            return codExt;
        }

        public void setCodExt(String codExt) {
            this.codExt = codExt;
        }

        public String getCodReject() {
            return codReject;
        }

        public void setCodReject(String codReject) {
            this.codReject = codReject;
        }

        public String getSfmsRejCode() {
            return sfmsRejCode;
        }

        public void setSfmsRejCode(String sfmsRejCode) {
            this.sfmsRejCode = sfmsRejCode;
        }

        public String getRefTxnNoRev() {
            return refTxnNoRev;
        }

        public void setRefTxnNoRev(String refTxnNoRev) {
            this.refTxnNoRev = refTxnNoRev;
        }
    }

    private ArrayList<CursorApBaNeftHostInq_1> cursorApBaNeftHostInq_1 =
            new ArrayList<CursorApBaNeftHostInq_1>();

    private void fetchCursorApBaNeftHostInq_1() throws SQLException {
        ResultSet resultSet = null;
        try {
            resultSet = getCursor(5);
            cursorApBaNeftHostInq_1.clear();
            if (resultSet == null) {
                return;
            }

            while (resultSet.next()) {
                CursorApBaNeftHostInq_1 cursorObj =
                        new CursorApBaNeftHostInq_1();
                cursorObj.setSourceTable(resultSet.getString(1));
                cursorObj.setQueryObservedAt(resultSet.getString(2));
                cursorObj.setScopeRowCount(resultSet.getString(3));
                cursorObj.setRefTxnNo(resultSet.getString(4));
                cursorObj.setRefSubseqNo(resultSet.getString(5));
                cursorObj.setRefNetworkNo(resultSet.getString(6));
                cursorObj.setRefUsrNo(resultSet.getString(7));
                cursorObj.setCodOrgBrn(resultSet.getString(8));
                cursorObj.setCodOrgBank(resultSet.getString(9));
                cursorObj.setCodNetworkId(resultSet.getString(10));
                cursorObj.setCodPaymentTxn(resultSet.getString(11));
                cursorObj.setPayDir(resultSet.getString(12));
                cursorObj.setCodChnlId(resultSet.getString(13));
                cursorObj.setAmtTxnTcy(resultSet.getString(14));
                cursorObj.setCodTxnCcy(resultSet.getString(15));
                cursorObj.setDatTxn(resultSet.getString(16));
                cursorObj.setDatInitiation(resultSet.getString(17));
                cursorObj.setDatPost(resultSet.getString(18));
                cursorObj.setDatValue(resultSet.getString(19));
                cursorObj.setDatActivation(resultSet.getString(20));
                cursorObj.setDatDispatch(resultSet.getString(21));
                cursorObj.setDatNtwkValue(resultSet.getString(22));
                cursorObj.setDatAuthtime(resultSet.getString(23));
                cursorObj.setDatSecondAuthtime(resultSet.getString(24));
                cursorObj.setTxnStat(resultSet.getString(25));
                cursorObj.setAcctStat(resultSet.getString(26));
                cursorObj.setMsgStat(resultSet.getString(27));
                cursorObj.setNtwkAcctStat(resultSet.getString(28));
                cursorObj.setContgAcctStat(resultSet.getString(29));
                cursorObj.setCtPartyAcctStat(resultSet.getString(30));
                cursorObj.setFlgPostCutoff(resultSet.getString(31));
                cursorObj.setCodReply(resultSet.getString(32));
                cursorObj.setCodExt(resultSet.getString(33));
                cursorObj.setCodReject(resultSet.getString(34));
                cursorObj.setSfmsRejCode(resultSet.getString(35));
                cursorObj.setRefTxnNoRev(resultSet.getString(36));
                cursorApBaNeftHostInq_1.add(cursorObj);
            }
        } finally {
            if (resultSet != null) {
                resultSet.close();
            }
        }
    }

    public class ReturnVOApBaNeftHostInq {

        private long returnValue;
        private String refTxnNumber;
        private Integer originatingBranchCode;
        private Integer originatingBankCode;

        public long getReturnValue() {
            return returnValue;
        }

        public void setReturnValue(long returnValue) {
            this.returnValue = returnValue;
        }

        public String getRefTxnNumber() {
            return refTxnNumber;
        }

        public void setRefTxnNumber(String refTxnNumber) {
            this.refTxnNumber = refTxnNumber;
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

        public CursorApBaNeftHostInq_1[] getCursor1() {
            CursorApBaNeftHostInq_1[] cursor =
                    new CursorApBaNeftHostInq_1[
                            cursorApBaNeftHostInq_1.size()];
            Iterator<CursorApBaNeftHostInq_1> iterator =
                    cursorApBaNeftHostInq_1.iterator();
            int index = 0;
            while (iterator.hasNext()) {
                cursor[index++] = iterator.next();
            }
            return cursor;
        }

        ReturnVOApBaNeftHostInq(PkBaNEFTEvidenceInquiry procedure)
                throws SQLException {
            setReturnValue(procedure.getLong(1));
            setRefTxnNumber(procedure.getString(2));
            setOriginatingBranchCode(
                    Integer.valueOf((int) procedure.getLong(3)));
            setOriginatingBankCode(
                    Integer.valueOf((int) procedure.getLong(4)));
            if (getReturnValue() == 0) {
                procedure.fetchCursorApBaNeftHostInq_1();
            }
        }
    }

    public class CursorApBaNeftHistoryInq_1 {

        private String sourceTable;
        private String queryObservedAt;
        private String scopeRowCount;
        private String refTxnNo;
        private String codOrgBrn;
        private String codOrgBank;
        private String datTxn;
        private String datPost;
        private String datValue;
        private String acctStat;
        private String msgStat;
        private String txnStat;
        private String ntwkAcctStat;
        private String contgAcctStat;
        private String codChnlId;

        public String getSourceTable() {
            return sourceTable;
        }

        public void setSourceTable(String sourceTable) {
            this.sourceTable = sourceTable;
        }

        public String getQueryObservedAt() {
            return queryObservedAt;
        }

        public void setQueryObservedAt(String queryObservedAt) {
            this.queryObservedAt = queryObservedAt;
        }

        public String getScopeRowCount() {
            return scopeRowCount;
        }

        public void setScopeRowCount(String scopeRowCount) {
            this.scopeRowCount = scopeRowCount;
        }

        public String getRefTxnNo() {
            return refTxnNo;
        }

        public void setRefTxnNo(String refTxnNo) {
            this.refTxnNo = refTxnNo;
        }

        public String getCodOrgBrn() {
            return codOrgBrn;
        }

        public void setCodOrgBrn(String codOrgBrn) {
            this.codOrgBrn = codOrgBrn;
        }

        public String getCodOrgBank() {
            return codOrgBank;
        }

        public void setCodOrgBank(String codOrgBank) {
            this.codOrgBank = codOrgBank;
        }

        public String getDatTxn() {
            return datTxn;
        }

        public void setDatTxn(String datTxn) {
            this.datTxn = datTxn;
        }

        public String getDatPost() {
            return datPost;
        }

        public void setDatPost(String datPost) {
            this.datPost = datPost;
        }

        public String getDatValue() {
            return datValue;
        }

        public void setDatValue(String datValue) {
            this.datValue = datValue;
        }

        public String getAcctStat() {
            return acctStat;
        }

        public void setAcctStat(String acctStat) {
            this.acctStat = acctStat;
        }

        public String getMsgStat() {
            return msgStat;
        }

        public void setMsgStat(String msgStat) {
            this.msgStat = msgStat;
        }

        public String getTxnStat() {
            return txnStat;
        }

        public void setTxnStat(String txnStat) {
            this.txnStat = txnStat;
        }

        public String getNtwkAcctStat() {
            return ntwkAcctStat;
        }

        public void setNtwkAcctStat(String ntwkAcctStat) {
            this.ntwkAcctStat = ntwkAcctStat;
        }

        public String getContgAcctStat() {
            return contgAcctStat;
        }

        public void setContgAcctStat(String contgAcctStat) {
            this.contgAcctStat = contgAcctStat;
        }

        public String getCodChnlId() {
            return codChnlId;
        }

        public void setCodChnlId(String codChnlId) {
            this.codChnlId = codChnlId;
        }
    }

    private ArrayList<CursorApBaNeftHistoryInq_1> cursorApBaNeftHistoryInq_1 =
            new ArrayList<CursorApBaNeftHistoryInq_1>();

    private void fetchCursorApBaNeftHistoryInq_1() throws SQLException {
        ResultSet resultSet = null;
        try {
            resultSet = getCursor(5);
            cursorApBaNeftHistoryInq_1.clear();
            if (resultSet == null) {
                return;
            }

            while (resultSet.next()) {
                CursorApBaNeftHistoryInq_1 cursorObj =
                        new CursorApBaNeftHistoryInq_1();
                cursorObj.setSourceTable(resultSet.getString(1));
                cursorObj.setQueryObservedAt(resultSet.getString(2));
                cursorObj.setScopeRowCount(resultSet.getString(3));
                cursorObj.setRefTxnNo(resultSet.getString(4));
                cursorObj.setCodOrgBrn(resultSet.getString(5));
                cursorObj.setCodOrgBank(resultSet.getString(6));
                cursorObj.setDatTxn(resultSet.getString(7));
                cursorObj.setDatPost(resultSet.getString(8));
                cursorObj.setDatValue(resultSet.getString(9));
                cursorObj.setAcctStat(resultSet.getString(10));
                cursorObj.setMsgStat(resultSet.getString(11));
                cursorObj.setTxnStat(resultSet.getString(12));
                cursorObj.setNtwkAcctStat(resultSet.getString(13));
                cursorObj.setContgAcctStat(resultSet.getString(14));
                cursorObj.setCodChnlId(resultSet.getString(15));
                cursorApBaNeftHistoryInq_1.add(cursorObj);
            }
        } finally {
            if (resultSet != null) {
                resultSet.close();
            }
        }
    }

    public class ReturnVOApBaNeftHistoryInq {

        private long returnValue;
        private String refTxnNumber;
        private Integer originatingBranchCode;
        private Integer originatingBankCode;

        public long getReturnValue() {
            return returnValue;
        }

        public void setReturnValue(long returnValue) {
            this.returnValue = returnValue;
        }

        public String getRefTxnNumber() {
            return refTxnNumber;
        }

        public void setRefTxnNumber(String refTxnNumber) {
            this.refTxnNumber = refTxnNumber;
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

        public CursorApBaNeftHistoryInq_1[] getCursor1() {
            CursorApBaNeftHistoryInq_1[] cursor =
                    new CursorApBaNeftHistoryInq_1[
                            cursorApBaNeftHistoryInq_1.size()];
            Iterator<CursorApBaNeftHistoryInq_1> iterator =
                    cursorApBaNeftHistoryInq_1.iterator();
            int index = 0;
            while (iterator.hasNext()) {
                cursor[index++] = iterator.next();
            }
            return cursor;
        }

        ReturnVOApBaNeftHistoryInq(PkBaNEFTEvidenceInquiry procedure)
                throws SQLException {
            setReturnValue(procedure.getLong(1));
            setRefTxnNumber(procedure.getString(2));
            setOriginatingBranchCode(
                    Integer.valueOf((int) procedure.getLong(3)));
            setOriginatingBankCode(
                    Integer.valueOf((int) procedure.getLong(4)));
            if (getReturnValue() == 0) {
                procedure.fetchCursorApBaNeftHistoryInq_1();
            }
        }
    }

    public class CursorApBaNeftStatusInq_1 {

        private String sourceTable;
        private String queryObservedAt;
        private String scopeRowCount;
        private String codStatus;
        private String msgStatus;
        private String acctStatus;
        private String neftCodStatus;

        public String getSourceTable() {
            return sourceTable;
        }

        public void setSourceTable(String sourceTable) {
            this.sourceTable = sourceTable;
        }

        public String getQueryObservedAt() {
            return queryObservedAt;
        }

        public void setQueryObservedAt(String queryObservedAt) {
            this.queryObservedAt = queryObservedAt;
        }

        public String getScopeRowCount() {
            return scopeRowCount;
        }

        public void setScopeRowCount(String scopeRowCount) {
            this.scopeRowCount = scopeRowCount;
        }

        public String getCodStatus() {
            return codStatus;
        }

        public void setCodStatus(String codStatus) {
            this.codStatus = codStatus;
        }

        public String getMsgStatus() {
            return msgStatus;
        }

        public void setMsgStatus(String msgStatus) {
            this.msgStatus = msgStatus;
        }

        public String getAcctStatus() {
            return acctStatus;
        }

        public void setAcctStatus(String acctStatus) {
            this.acctStatus = acctStatus;
        }

        public String getNeftCodStatus() {
            return neftCodStatus;
        }

        public void setNeftCodStatus(String neftCodStatus) {
            this.neftCodStatus = neftCodStatus;
        }
    }

    private ArrayList<CursorApBaNeftStatusInq_1> cursorApBaNeftStatusInq_1 =
            new ArrayList<CursorApBaNeftStatusInq_1>();

    private void fetchCursorApBaNeftStatusInq_1() throws SQLException {
        ResultSet resultSet = null;
        try {
            resultSet = getCursor(5);
            cursorApBaNeftStatusInq_1.clear();
            if (resultSet == null) {
                return;
            }

            while (resultSet.next()) {
                CursorApBaNeftStatusInq_1 cursorObj =
                        new CursorApBaNeftStatusInq_1();
                cursorObj.setSourceTable(resultSet.getString(1));
                cursorObj.setQueryObservedAt(resultSet.getString(2));
                cursorObj.setScopeRowCount(resultSet.getString(3));
                cursorObj.setCodStatus(resultSet.getString(4));
                cursorObj.setMsgStatus(resultSet.getString(5));
                cursorObj.setAcctStatus(resultSet.getString(6));
                cursorObj.setNeftCodStatus(resultSet.getString(7));
                cursorApBaNeftStatusInq_1.add(cursorObj);
            }
        } finally {
            if (resultSet != null) {
                resultSet.close();
            }
        }
    }

    public class ReturnVOApBaNeftStatusInq {

        private long returnValue;
        private String refTxnNumber;
        private Integer originatingBranchCode;
        private Integer originatingBankCode;

        public long getReturnValue() {
            return returnValue;
        }

        public void setReturnValue(long returnValue) {
            this.returnValue = returnValue;
        }

        public String getRefTxnNumber() {
            return refTxnNumber;
        }

        public void setRefTxnNumber(String refTxnNumber) {
            this.refTxnNumber = refTxnNumber;
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

        public CursorApBaNeftStatusInq_1[] getCursor1() {
            CursorApBaNeftStatusInq_1[] cursor =
                    new CursorApBaNeftStatusInq_1[
                            cursorApBaNeftStatusInq_1.size()];
            Iterator<CursorApBaNeftStatusInq_1> iterator =
                    cursorApBaNeftStatusInq_1.iterator();
            int index = 0;
            while (iterator.hasNext()) {
                cursor[index++] = iterator.next();
            }
            return cursor;
        }

        ReturnVOApBaNeftStatusInq(PkBaNEFTEvidenceInquiry procedure)
                throws SQLException {
            setReturnValue(procedure.getLong(1));
            setRefTxnNumber(procedure.getString(2));
            setOriginatingBranchCode(
                    Integer.valueOf((int) procedure.getLong(3)));
            setOriginatingBankCode(
                    Integer.valueOf((int) procedure.getLong(4)));
            if (getReturnValue() == 0) {
                procedure.fetchCursorApBaNeftStatusInq_1();
            }
        }
    }
}
