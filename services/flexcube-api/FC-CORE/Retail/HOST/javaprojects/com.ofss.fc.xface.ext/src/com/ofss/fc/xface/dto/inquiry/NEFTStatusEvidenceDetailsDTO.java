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

public class NEFTStatusEvidenceDetailsDTO extends NEFTEvidenceRecordDTO {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Payment status code")
    private String codStatus;

    @ApiModelProperty(value = "Message status code")
    private String msgStatus;

    @ApiModelProperty(value = "Account status code")
    private String acctStatus;

    @ApiModelProperty(value = "Mapped NEFT status")
    private String neftCodStatus;

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

