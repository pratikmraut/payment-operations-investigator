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

public class NEFTHistoryEvidenceDetailsDTO extends NEFTEvidenceRecordDTO {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Reference transaction number")
    private String refTxnNo;

    @ApiModelProperty(value = "Originating branch code")
    private String codOrgBrn;

    @ApiModelProperty(value = "Originating bank code")
    private String codOrgBank;

    @ApiModelProperty(value = "Transaction timestamp")
    private String datTxn;

    @ApiModelProperty(value = "Posting timestamp")
    private String datPost;

    @ApiModelProperty(value = "Value timestamp")
    private String datValue;

    @ApiModelProperty(value = "Account status code")
    private String acctStat;

    @ApiModelProperty(value = "Message status code")
    private String msgStat;

    @ApiModelProperty(value = "Transaction status code")
    private String txnStat;

    @ApiModelProperty(value = "Network account status code")
    private String ntwkAcctStat;

    @ApiModelProperty(value = "Contingent account status code")
    private String contgAcctStat;

    @ApiModelProperty(value = "Channel identifier")
    private String codChnlId;

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

