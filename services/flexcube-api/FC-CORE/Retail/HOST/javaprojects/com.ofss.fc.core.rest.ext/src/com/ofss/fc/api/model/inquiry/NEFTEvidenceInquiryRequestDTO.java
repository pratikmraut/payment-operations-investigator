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

package com.ofss.fc.api.model.inquiry;

import com.ofss.fc.app.context.SessionContext;
import com.ofss.fc.xface.dto.inquiry.XfaceNEFTEvidenceInquiryRequestDTO;

import io.swagger.annotations.ApiModelProperty;

public class NEFTEvidenceInquiryRequestDTO {

    @ApiModelProperty(value = "Describes the details of the session",
            required = true)
    private SessionContext args0;

    @ApiModelProperty(value = "NEFT evidence inquiry request",
            required = true)
    private XfaceNEFTEvidenceInquiryRequestDTO args1;

    public SessionContext getArgs0() {
        return args0;
    }

    public void setArgs0(SessionContext args0) {
        this.args0 = args0;
    }

    public XfaceNEFTEvidenceInquiryRequestDTO getArgs1() {
        return args1;
    }

    public void setArgs1(XfaceNEFTEvidenceInquiryRequestDTO args1) {
        this.args1 = args1;
    }
}

