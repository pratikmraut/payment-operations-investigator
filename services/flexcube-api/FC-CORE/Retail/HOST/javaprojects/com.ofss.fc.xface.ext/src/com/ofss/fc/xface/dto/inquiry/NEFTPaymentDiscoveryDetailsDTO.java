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
Copyright c [2010] Oracle Financial Services Limited.
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

package com.ofss.fc.xface.dto.inquiry;

import java.io.Serializable;

import io.swagger.annotations.ApiModelProperty;

public class NEFTPaymentDiscoveryDetailsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "NEFT reference transaction number")
    private String referenceTransactionNumber;

    @ApiModelProperty(value = "Originating branch code for the discovered payment")
    private String originatingBranchCode;

    @ApiModelProperty(value = "Originating bank code for the discovered payment")
    private String originatingBankCode;

    @ApiModelProperty(value = "Host transaction subsequence number")
    private String referenceSubsequenceNumber;

    @ApiModelProperty(value = "NEFT UTR reference number")
    private String utrReferenceNumber;

    @ApiModelProperty(value = "NEFT initiation timestamp in ISO-8601 local date-time format")
    private String initiationDateTime;

    @ApiModelProperty(value = "Transaction amount represented as database export text")
    private String transactionAmount;

    public String getReferenceTransactionNumber() {
        return referenceTransactionNumber;
    }

    public void setReferenceTransactionNumber(String referenceTransactionNumber) {
        this.referenceTransactionNumber = referenceTransactionNumber;
    }

    public String getOriginatingBranchCode() {
        return originatingBranchCode;
    }

    public void setOriginatingBranchCode(String originatingBranchCode) {
        this.originatingBranchCode = originatingBranchCode;
    }

    public String getOriginatingBankCode() {
        return originatingBankCode;
    }

    public void setOriginatingBankCode(String originatingBankCode) {
        this.originatingBankCode = originatingBankCode;
    }

    public String getReferenceSubsequenceNumber() {
        return referenceSubsequenceNumber;
    }

    public void setReferenceSubsequenceNumber(String referenceSubsequenceNumber) {
        this.referenceSubsequenceNumber = referenceSubsequenceNumber;
    }

    public String getUtrReferenceNumber() {
        return utrReferenceNumber;
    }

    public void setUtrReferenceNumber(String utrReferenceNumber) {
        this.utrReferenceNumber = utrReferenceNumber;
    }

    public String getInitiationDateTime() {
        return initiationDateTime;
    }

    public void setInitiationDateTime(String initiationDateTime) {
        this.initiationDateTime = initiationDateTime;
    }

    public String getTransactionAmount() {
        return transactionAmount;
    }

    public void setTransactionAmount(String transactionAmount) {
        this.transactionAmount = transactionAmount;
    }
}
