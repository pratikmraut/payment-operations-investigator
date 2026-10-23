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
 13/09/2026  1.0         pmraut          PO02 NEFT Evidence Inquiry API
 -------------------------------------------------------------------------------*/

package com.ofss.fc.api.controller.inquiry;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.ofss.fc.api.model.inquiry.NEFTEvidenceInquiryRequestDTO;
import com.ofss.fc.app.context.SessionContext;
import com.ofss.fc.xface.dto.inquiry.XfaceNEFTEvidenceInquiryRequestDTO;
import com.ofss.fc.xface.dto.inquiry.XfaceNEFTEvidenceInquiryResponseDTO;
import com.ofss.fc.xface.wrapper.inquiry.XfaceNEFTEvidenceInquiryWrapper;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

@Path("/NEFTEvidenceInquiryService")
@Api(description = "NEFT Evidence Inquiry Service")
public class NEFTEvidenceInquiryWrapperController {

    private XfaceNEFTEvidenceInquiryWrapper wrapperObj =
            new XfaceNEFTEvidenceInquiryWrapper();

    @POST
    @Path("/processRequest")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Retrieve NEFT payment evidence", notes = "", tags = {
            "XfaceServices" })
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successful operation",
                    response = XfaceNEFTEvidenceInquiryResponseDTO.class) })
    public XfaceNEFTEvidenceInquiryResponseDTO processNEFTEvidenceInquiryRequest(
            NEFTEvidenceInquiryRequestDTO requestDTO) {
        SessionContext args0 = requestDTO.getArgs0();
        XfaceNEFTEvidenceInquiryRequestDTO args1 = requestDTO.getArgs1();
        return wrapperObj.processRequest(args0, args1);
    }
}
