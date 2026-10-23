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

import com.ofss.fc.xface.dto.common.XfaceGenericResponseDTO;

import io.swagger.annotations.ApiModelProperty;

public class XfaceNEFTEvidenceInquiryResponseDTO
        extends XfaceGenericResponseDTO {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "NEFT payment evidence details")
    private NEFTPaymentEvidenceDetailsDTO[] neftPaymentEvidenceDetails;

    @ApiModelProperty(value = "NEFT host evidence details")
    private NEFTHostEvidenceDetailsDTO[] neftHostEvidenceDetails;

    @ApiModelProperty(value = "NEFT history evidence details")
    private NEFTHistoryEvidenceDetailsDTO[] neftHistoryEvidenceDetails;

    @ApiModelProperty(value = "NEFT status evidence details")
    private NEFTStatusEvidenceDetailsDTO[] neftStatusEvidenceDetails;

    public NEFTPaymentEvidenceDetailsDTO[] getNeftPaymentEvidenceDetails() {
        return neftPaymentEvidenceDetails;
    }

    public void setNeftPaymentEvidenceDetails(NEFTPaymentEvidenceDetailsDTO[] neftPaymentEvidenceDetails) {
        this.neftPaymentEvidenceDetails = neftPaymentEvidenceDetails;
    }

    public NEFTHostEvidenceDetailsDTO[] getNeftHostEvidenceDetails() {
        return neftHostEvidenceDetails;
    }

    public void setNeftHostEvidenceDetails(NEFTHostEvidenceDetailsDTO[] neftHostEvidenceDetails) {
        this.neftHostEvidenceDetails = neftHostEvidenceDetails;
    }

    public NEFTHistoryEvidenceDetailsDTO[] getNeftHistoryEvidenceDetails() {
        return neftHistoryEvidenceDetails;
    }

    public void setNeftHistoryEvidenceDetails(NEFTHistoryEvidenceDetailsDTO[] neftHistoryEvidenceDetails) {
        this.neftHistoryEvidenceDetails = neftHistoryEvidenceDetails;
    }

    public NEFTStatusEvidenceDetailsDTO[] getNeftStatusEvidenceDetails() {
        return neftStatusEvidenceDetails;
    }

    public void setNeftStatusEvidenceDetails(NEFTStatusEvidenceDetailsDTO[] neftStatusEvidenceDetails) {
        this.neftStatusEvidenceDetails = neftStatusEvidenceDetails;
    }
}

