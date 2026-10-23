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
 14/09/2026  1.0         pmraut          PO01 NEFT Payment Discovery Inquiry API
 -------------------------------------------------------------------------------*/

package com.ofss.fc.xface.wrapper.inquiry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import com.ofss.fc.app.Interaction;
import com.ofss.fc.app.context.SessionContext;
import com.ofss.fc.common.FCRJErrorConstants;
import com.ofss.fc.datatype.Date;
import com.ofss.fc.entity.transaction.TransactionStatus;
import com.ofss.fc.infra.error.ErrorManager;
import com.ofss.fc.infra.exception.FatalException;
import com.ofss.fc.infra.log.impl.TraceLogger;
import com.ofss.fc.infra.validation.error.ValidationError;
import com.ofss.fc.xface.core.AbstractGenericWrapper;
import com.ofss.fc.xface.dto.inquiry.XfaceNEFTPaymentDiscoveryInquiryRequestDTO;
import com.ofss.fc.xface.dto.inquiry.XfaceNEFTPaymentDiscoveryInquiryResponseDTO;
import com.ofss.fc.xface.entity.IXfaceTxnChnlXref;

/**
 * Runs the standard Xface validation, enrichment and DTO logging lifecycle for
 * the PO01 NEFT payment discovery inquiry.
 */
public class XfaceNEFTPaymentDiscoveryInquiryWrapper
        extends AbstractGenericWrapper {

    private XfaceNEFTPaymentDiscoveryInquiryWrapperImpl mainInstance =
            new XfaceNEFTPaymentDiscoveryInquiryWrapperImpl();

    public XfaceNEFTPaymentDiscoveryInquiryResponseDTO processRequest(
            SessionContext sessionContext,
            XfaceNEFTPaymentDiscoveryInquiryRequestDTO requestDto) {

        Date requestTime = new Date();
        byte[] requestData = null;
        byte[] responseData = null;
        byte[] enrichedRequestData = null;

        XfaceNEFTPaymentDiscoveryInquiryResponseDTO response =
                new XfaceNEFTPaymentDiscoveryInquiryResponseDTO();
        TransactionStatus status = fetchTransactionStatus();
        response.setTransactionStatus(status);

        try {
            isSessionOpen();
            Interaction.begin(sessionContext);
            IXfaceTxnChnlXref xfaceTxnChnlXref =
                    loadTransactionChannelXref(sessionContext);
            Interaction.markCurrentTask(xfaceTxnChnlXref.getCodTask());

            requestData = serializeDTO(requestDto);
            HashMap requestHash = populateRequestTable(requestDto);

            ArrayList validationCodes = new ArrayList();
            if (requestDto.getValidationFlag() != null) {
                String[] validationFlagValues = requestDto.getValidationFlag()
                        .trim().split("(?<=\\G.{5})");
                validationCodes.addAll(Arrays.asList(validationFlagValues));
            }

            TraceLogger.performance(
                    XfaceNEFTPaymentDiscoveryInquiryWrapper.class
                            .getCanonicalName(),
                    "", "Start of validation engine : Start time "
                            + System.currentTimeMillis());
            ValidationError[] errors = processRequest(sessionContext, requestDto,
                    requestHash, validationCodes);
            TraceLogger.performance(
                    XfaceNEFTPaymentDiscoveryInquiryWrapper.class
                            .getCanonicalName(),
                    "", "End of validation engine : End time "
                            + System.currentTimeMillis());

            if (errors == null || errors.length == 0) {
                requestDto = populateRequestDTO(requestHash, requestDto);
                enrichedRequestData = serializeDTO(requestDto);
                response = mainInstance.processRequest(sessionContext, requestDto);

                if (response != null
                        && isResponseEnrichmentRequired(sessionContext)) {
                    HashMap responseHash = populateResponseTable(response);
                    doResponseEnrichment(sessionContext, response, responseHash);
                    response = populateResponseDTO(responseHash, response);
                }
            } else {
                status.setValidationErrors(errors);
                status.setExternalReferenceNo(
                        sessionContext.getExternalReferenceNo());
                status.setErrorCode(FCRJErrorConstants.MID_VALIDATION_FAILED);
                status.setReplyCode(Long.parseLong(
                        ErrorManager.DEFAULT_ERR_RESPONSE.getEnumValue()));
                status.setReplyText(ErrorManager.buildErrorMessage(
                        FCRJErrorConstants.MID_VALIDATION_FAILED,
                        new String[] { String.valueOf(errors.length) }, null));
            }
        } catch (FatalException e) {
            ErrorManager.logException(sessionContext.getServiceCode(), e);
            fillTransactionStatus(response.getTransactionStatus(), e);
        } catch (Throwable e) {
            ErrorManager.logException(sessionContext.getServiceCode(), e);
            fillTransactionStatus(response.getTransactionStatus(), e);
        } finally {
            Date responseTime = new Date();
            try {
                response.setPostingDate(
                        sessionContext.getPostingDateText().substring(0, 8));
                responseData = serializeDTO(response);
                doDTOlogging(sessionContext, requestData, requestTime, responseTime,
                        responseData, enrichedRequestData);
            } catch (Throwable e) {
                ErrorManager.logException(sessionContext.getServiceCode(), e);
            } finally {
                try {
                    Interaction.close();
                } catch (FatalException e) {
                    ErrorManager.logException(sessionContext.getServiceCode(), e);
                }
            }
        }
        return response;
    }

    private XfaceNEFTPaymentDiscoveryInquiryRequestDTO populateRequestDTO(
            HashMap requestHash,
            XfaceNEFTPaymentDiscoveryInquiryRequestDTO requestDto) {

        requestDto.setOriginatingBranchCode(asInteger(requestHash.get(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/"
                        + "originatingBranchCode")));
        requestDto.setOriginatingBankCode(asInteger(requestHash.get(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/"
                        + "originatingBankCode")));
        requestDto.setReferenceType((String) requestHash.get(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/referenceType"));
        requestDto.setReference((String) requestHash.get(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/reference"));
        requestDto.setInquiryDate((String) requestHash.get(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/inquiryDate"));
        requestDto.setRecordCount(asInteger(requestHash.get(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/recordCount")));
        return requestDto;
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return Integer.valueOf(((Number) value).intValue());
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private HashMap populateRequestTable(
            XfaceNEFTPaymentDiscoveryInquiryRequestDTO requestDto) {

        HashMap validationMap = new HashMap();
        validationMap.put(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/"
                        + "originatingBranchCode",
                requestDto.getOriginatingBranchCode());
        validationMap.put(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/"
                        + "originatingBankCode",
                requestDto.getOriginatingBankCode());
        validationMap.put(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/referenceType",
                requestDto.getReferenceType());
        validationMap.put(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/reference",
                requestDto.getReference());
        validationMap.put(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/inquiryDate",
                requestDto.getInquiryDate());
        validationMap.put(
                "XfaceNEFTPaymentDiscoveryInquiryRequestDTO/recordCount",
                requestDto.getRecordCount());
        return validationMap;
    }

    private HashMap populateResponseTable(
            XfaceNEFTPaymentDiscoveryInquiryResponseDTO responseDto) {

        HashMap responseMap = new HashMap();
        responseMap.put(
                "XfaceNEFTPaymentDiscoveryInquiryResponseDTO/observedAt",
                responseDto.getObservedAt());
        responseMap.put(
                "XfaceNEFTPaymentDiscoveryInquiryResponseDTO/hasMoreRecords",
                responseDto.getHasMoreRecords());
        responseMap.put(
                "XfaceNEFTPaymentDiscoveryInquiryResponseDTO/"
                        + "neftPaymentDiscoveryDetails",
                responseDto.getNeftPaymentDiscoveryDetails());
        return responseMap;
    }

    private XfaceNEFTPaymentDiscoveryInquiryResponseDTO populateResponseDTO(
            HashMap responseHash,
            XfaceNEFTPaymentDiscoveryInquiryResponseDTO responseDto) {

        /*
         * PO01 currently has no RES api_txn_data rows. Retain the typed
         * response assembled by the implementation; response-side mappings can
         * be introduced with matching RES metadata if required.
         */
        return responseDto;
    }
}
