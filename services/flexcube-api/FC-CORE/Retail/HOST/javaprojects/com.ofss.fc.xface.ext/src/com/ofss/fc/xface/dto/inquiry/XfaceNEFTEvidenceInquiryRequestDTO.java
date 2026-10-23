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

import com.ofss.fc.xface.dto.common.XfaceGenericRequestDTO;

import io.swagger.annotations.ApiModelProperty;

public class XfaceNEFTEvidenceInquiryRequestDTO
        extends XfaceGenericRequestDTO {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "NEFT reference transaction number", required = true)
    private String referenceTransactionNumber;

    @ApiModelProperty(value = "Originating branch code", required = true)
    private Integer originatingBranchCode;

    @ApiModelProperty(value = "Originating bank code", required = true)
    private Integer originatingBankCode;

    public String getReferenceTransactionNumber() {
        return referenceTransactionNumber;
    }

    public void setReferenceTransactionNumber(String referenceTransactionNumber) {
        this.referenceTransactionNumber = referenceTransactionNumber;
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
}

