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

public class NEFTPaymentEvidenceDetailsDTO extends NEFTEvidenceRecordDTO {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "NEFT reference transaction number")
    private String refTxnNumber;

    @ApiModelProperty(value = "ISO 2020 user reference identifier")
    private String idUserReference2020;

    @ApiModelProperty(value = "ISO 2006 related reference identifier")
    private String idRelatedRef2006;

    @ApiModelProperty(value = "Unique Transaction Reference number")
    private String utrRefNo;

    @ApiModelProperty(value = "ISO 2020 message reference identifier")
    private String idMsgReference2020;

    @ApiModelProperty(value = "Message sequence number")
    private String idSequenceNo;

    @ApiModelProperty(value = "NEFT transaction amount as exported text")
    private String numAmount4038;

    @ApiModelProperty(value = "Transaction currency code")
    private String codCurr;

    @ApiModelProperty(value = "Payment direction")
    private String direction;

    @ApiModelProperty(value = "Payment initiation timestamp")
    private String datInitiation;

    @ApiModelProperty(value = "Payment value timestamp")
    private String datValue3380;

    @ApiModelProperty(value = "Message type")
    private String msgType;

    @ApiModelProperty(value = "Message subtype")
    private String subMsgType;

    @ApiModelProperty(value = "Transaction type")
    private String txnType;

    @ApiModelProperty(value = "Payment status code")
    private String codStatus;

    @ApiModelProperty(value = "Account status code")
    private String acctStatus;

    @ApiModelProperty(value = "Message status code")
    private String msgStatus;

    @ApiModelProperty(value = "Retry count")
    private String numRetry;

    @ApiModelProperty(value = "Batch processing time")
    private String batchTime3535;

    @ApiModelProperty(value = "Payment reason code")
    private String reasonCode6346;

    @ApiModelProperty(value = "N10 message identifier")
    private String n10MsgId;

    @ApiModelProperty(value = "N10 sequence number")
    private String n10IdSequenceNo;

    @ApiModelProperty(value = "N10 transaction identifier")
    private String n10TxnId;

    @ApiModelProperty(value = "N10 status")
    private String n10Status;

    @ApiModelProperty(value = "N10 event timestamp")
    private String n10DateTime;

    @ApiModelProperty(value = "N10 receive or sent timestamp")
    private String n10RecvSentDateTime;

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

