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

import java.io.Serializable;

import io.swagger.annotations.ApiModelProperty;

public abstract class NEFTEvidenceRecordDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Source table used by the evidence query")
    private String sourceTable;

    @ApiModelProperty(value = "Timestamp at which the evidence query was observed")
    private String queryObservedAt;

    @ApiModelProperty(value = "Total records in the evidence result group")
    private String scopeRowCount;

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getQueryObservedAt() {
        return queryObservedAt;
    }

    public void setQueryObservedAt(String queryObservedAt) {
        this.queryObservedAt = queryObservedAt;
    }

    public String getScopeRowCount() {
        return scopeRowCount;
    }

    public void setScopeRowCount(String scopeRowCount) {
        this.scopeRowCount = scopeRowCount;
    }
}

