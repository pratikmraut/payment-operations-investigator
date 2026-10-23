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
13/09/2026  1.0         pmraut          PO02 NEFT Evidence Inquiry API
-------------------------------------------------------------------------------*/

package com.ofss.fc.xface.dto.inquiry;

import io.swagger.annotations.ApiModelProperty;

public class NEFTHostEvidenceDetailsDTO extends NEFTEvidenceRecordDTO {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Reference transaction number")
    private String refTxnNo;

    @ApiModelProperty(value = "Reference transaction subsequence number")
    private String refSubseqNo;

    @ApiModelProperty(value = "Reference network number")
    private String refNetworkNo;

    @ApiModelProperty(value = "Reference user number")
    private String refUsrNo;

    @ApiModelProperty(value = "Originating branch code")
    private String codOrgBrn;

    @ApiModelProperty(value = "Originating bank code")
    private String codOrgBank;

    @ApiModelProperty(value = "Network identifier")
    private String codNetworkId;

    @ApiModelProperty(value = "Payment transaction code")
    private String codPaymentTxn;

    @ApiModelProperty(value = "Payment direction")
    private String payDir;

    @ApiModelProperty(value = "Channel identifier")
    private String codChnlId;

    @ApiModelProperty(value = "Transaction amount in transaction currency")
    private String amtTxnTcy;

    @ApiModelProperty(value = "Transaction currency code")
    private String codTxnCcy;

    @ApiModelProperty(value = "Transaction timestamp")
    private String datTxn;

    @ApiModelProperty(value = "Initiation timestamp")
    private String datInitiation;

    @ApiModelProperty(value = "Posting timestamp")
    private String datPost;

    @ApiModelProperty(value = "Value timestamp")
    private String datValue;

    @ApiModelProperty(value = "Activation timestamp")
    private String datActivation;

    @ApiModelProperty(value = "Dispatch timestamp")
    private String datDispatch;

    @ApiModelProperty(value = "Network value timestamp")
    private String datNtwkValue;

    @ApiModelProperty(value = "Authorization timestamp")
    private String datAuthtime;

    @ApiModelProperty(value = "Second authorization timestamp")
    private String datSecondAuthtime;

    @ApiModelProperty(value = "Transaction status code")
    private String txnStat;

    @ApiModelProperty(value = "Account status code")
    private String acctStat;

    @ApiModelProperty(value = "Message status code")
    private String msgStat;

    @ApiModelProperty(value = "Network account status code")
    private String ntwkAcctStat;

    @ApiModelProperty(value = "Contingent account status code")
    private String contgAcctStat;

    @ApiModelProperty(value = "Counterparty account status code")
    private String ctPartyAcctStat;

    @ApiModelProperty(value = "Post-cutoff flag")
    private String flgPostCutoff;

    @ApiModelProperty(value = "Reply code")
    private String codReply;

    @ApiModelProperty(value = "External code")
    private String codExt;

    @ApiModelProperty(value = "Rejection code")
    private String codReject;

    @ApiModelProperty(value = "SFMS rejection code")
    private String sfmsRejCode;

    @ApiModelProperty(value = "Reversed reference transaction number")
    private String refTxnNoRev;

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

