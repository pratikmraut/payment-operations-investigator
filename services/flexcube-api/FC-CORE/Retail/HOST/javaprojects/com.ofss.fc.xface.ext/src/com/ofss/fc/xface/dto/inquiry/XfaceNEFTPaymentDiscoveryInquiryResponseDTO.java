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

import com.ofss.fc.xface.dto.common.XfaceGenericResponseDTO;

import io.swagger.annotations.ApiModelProperty;

public class XfaceNEFTPaymentDiscoveryInquiryResponseDTO
        extends XfaceGenericResponseDTO {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Timestamp at which the discovery query was observed")
    private String observedAt;

    @ApiModelProperty(value = "True when an additional distinct payment reference matched a date-based list inquiry")
    private Boolean hasMoreRecords;

    @ApiModelProperty(value = "NEFT payment discovery details")
    private NEFTPaymentDiscoveryDetailsDTO[] neftPaymentDiscoveryDetails;

    public String getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(String observedAt) {
        this.observedAt = observedAt;
    }

    public Boolean getHasMoreRecords() {
        return hasMoreRecords;
    }

    public void setHasMoreRecords(Boolean hasMoreRecords) {
        this.hasMoreRecords = hasMoreRecords;
    }

    public NEFTPaymentDiscoveryDetailsDTO[] getNeftPaymentDiscoveryDetails() {
        return neftPaymentDiscoveryDetails;
    }

    public void setNeftPaymentDiscoveryDetails(
            NEFTPaymentDiscoveryDetailsDTO[] neftPaymentDiscoveryDetails) {
        this.neftPaymentDiscoveryDetails = neftPaymentDiscoveryDetails;
    }
}
