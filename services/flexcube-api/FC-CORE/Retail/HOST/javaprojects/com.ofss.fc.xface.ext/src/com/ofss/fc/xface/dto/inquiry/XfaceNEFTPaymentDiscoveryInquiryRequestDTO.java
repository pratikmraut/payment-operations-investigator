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

import com.ofss.fc.xface.dto.common.XfaceGenericRequestDTO;

import io.swagger.annotations.ApiModelProperty;

public class XfaceNEFTPaymentDiscoveryInquiryRequestDTO
        extends XfaceGenericRequestDTO {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Originating branch code", required = true)
    private Integer originatingBranchCode;

    @ApiModelProperty(value = "Originating bank code", required = true)
    private Integer originatingBankCode;

    @ApiModelProperty(value = "Exact lookup type: FCR or UTR. Required with reference for exact lookup; omit for date-based list lookup")
    private String referenceType;

    @ApiModelProperty(value = "Exact FCR or UTR reference. Required with referenceType for exact lookup; omit for date-based list lookup")
    private String reference;

    @ApiModelProperty(value = "Date-based list lookup date in strict yyyy-MM-dd format. Required with recordCount for list lookup; omit for exact lookup")
    private String inquiryDate;

    @ApiModelProperty(value = "Maximum number of distinct payment references to return, from 1 through 200. Required with inquiryDate for list lookup; omit for exact lookup")
    private Integer recordCount;

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

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getInquiryDate() {
        return inquiryDate;
    }

    public void setInquiryDate(String inquiryDate) {
        this.inquiryDate = inquiryDate;
    }

    public Integer getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(Integer recordCount) {
        this.recordCount = recordCount;
    }
}
