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

import java.sql.Date;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ofss.fc.app.Interaction;
import com.ofss.fc.app.context.SessionContext;
import com.ofss.fc.common.FCRJErrorConstants;
import com.ofss.fc.entity.transaction.TransactionStatus;
import com.ofss.fc.infra.error.ErrorManager;
import com.ofss.fc.infra.exception.FatalException;
import com.ofss.fc.infra.log.MessageLogger;
import com.ofss.fc.xface.common.AbstractXfaceApplication;
import com.ofss.fc.xface.db.procedure.impl.PkBaNEFTDiscoveryInquiry;
import com.ofss.fc.xface.db.procedure.impl.PkBaNEFTDiscoveryInquiry.CursorApBaNeftDiscoveryInq_1;
import com.ofss.fc.xface.dto.inquiry.NEFTPaymentDiscoveryDetailsDTO;
import com.ofss.fc.xface.dto.inquiry.XfaceNEFTPaymentDiscoveryInquiryRequestDTO;
import com.ofss.fc.xface.dto.inquiry.XfaceNEFTPaymentDiscoveryInquiryResponseDTO;

/**
 * Applies PO01 request rules and maps the discovery cursor returned by
 * PK_BA_NEFT_EVIDENCE_INQ.  List mode returns a one-payment-group look-ahead;
 * that group is removed here and represented by hasMoreRecords.
 */
public class XfaceNEFTPaymentDiscoveryInquiryWrapperImpl
        extends AbstractXfaceApplication {

    private static final int MAX_DISCOVERY_PAYMENT_GROUPS = 200;

    public XfaceNEFTPaymentDiscoveryInquiryResponseDTO processRequest(
            SessionContext sessionContext,
            XfaceNEFTPaymentDiscoveryInquiryRequestDTO request)
            throws FatalException {

        final String methodName = "processRequest";
        XfaceNEFTPaymentDiscoveryInquiryResponseDTO response =
                new XfaceNEFTPaymentDiscoveryInquiryResponseDTO();
        TransactionStatus status = fetchTransactionStatus();

        try {
            Interaction.begin(sessionContext);
            validateRequest(sessionContext, request);
            response = fetchNeftPaymentDiscovery(sessionContext, request);
            fillTransactionStatus(status);
        } catch (FatalException e) {
            ErrorManager.logException(sessionContext.getServiceCode(), e);
            fillTransactionStatus(status, e);
        } catch (Throwable e) {
            ErrorManager.logException(sessionContext.getServiceCode(), e);
            fillTransactionStatus(status, e);
        } finally {
            Interaction.close();
            response.setTransactionStatus(status);
            MessageLogger.performance(THIS_COMPONENT_NAME, methodName,
                    " ends at:" + System.currentTimeMillis());
        }
        return response;
    }

    private XfaceNEFTPaymentDiscoveryInquiryResponseDTO
            fetchNeftPaymentDiscovery(SessionContext sessionContext,
                    XfaceNEFTPaymentDiscoveryInquiryRequestDTO request)
                    throws FatalException {

        try {
            PkBaNEFTDiscoveryInquiry storedProc =
                    new PkBaNEFTDiscoveryInquiry();
            PkBaNEFTDiscoveryInquiry.ReturnVOApBaNeftDiscoveryInq returnVO =
                    storedProc.execApBaNeftDiscoveryInq(
                            request.getOriginatingBranchCode(),
                            request.getOriginatingBankCode(),
                            request.getReferenceType(), request.getReference(),
                            toInquiryDate(request.getInquiryDate()),
                            request.getRecordCount());
            verifyReturnCode(returnVO.getReturnValue());

            LinkedHashMap<String, List<CursorApBaNeftDiscoveryInq_1>>
                    paymentGroups = groupByPaymentReference(
                            returnVO.getCursor1());
            boolean exactLookup = hasExactReferenceInput(request);

            if (exactLookup && paymentGroups.size()
                    > MAX_DISCOVERY_PAYMENT_GROUPS) {
                throwValidationError(
                        "Exact reference lookup returned more than 200 payment references");
            }

            int requestedPaymentGroups = exactLookup
                    ? MAX_DISCOVERY_PAYMENT_GROUPS
                    : request.getRecordCount().intValue();
            boolean hasMoreRecords = !exactLookup && paymentGroups.size()
                    > requestedPaymentGroups;
            CursorApBaNeftDiscoveryInq_1[] retainedRows =
                    retainPaymentGroups(paymentGroups, requestedPaymentGroups);

            XfaceNEFTPaymentDiscoveryInquiryResponseDTO response =
                    new XfaceNEFTPaymentDiscoveryInquiryResponseDTO();
            response.setObservedAt(returnVO.getObservedAt());
            response.setHasMoreRecords(Boolean.valueOf(hasMoreRecords));
            response.setNeftPaymentDiscoveryDetails(
                    mapDiscoveryDetails(retainedRows));
            return response;
        } catch (SQLException e) {
            ErrorManager.logException(sessionContext.getServiceCode(), e);
            ErrorManager.throwFatalException(
                    FCRJErrorConstants.MID_DUMMY_ERROR_CODE,
                    new String[] {
                            "Unable to retrieve NEFT payment discovery details",
                            "", "" }, null, null);
            return null;
        }
    }

    private void verifyReturnCode(long returnValue) throws FatalException {

        if (returnValue != 0) {
            ErrorManager.throwFatalException(
                    FCRJErrorConstants.MID_DUMMY_ERROR_CODE,
                    new String[] {
                            "Unable to retrieve NEFT payment discovery details",
                            "", "" }, null, null);
        }
    }

    private void validateRequest(SessionContext sessionContext,
            XfaceNEFTPaymentDiscoveryInquiryRequestDTO request)
            throws FatalException {

        if (sessionContext == null) {
            throwValidationError("Session context is required");
        }
        if (request == null || request.getOriginatingBranchCode() == null
                || request.getOriginatingBankCode() == null) {
            throwValidationError(
                    "originatingBranchCode and originatingBankCode are required");
        }
        if (request.getOriginatingBranchCode().intValue() < 0
                || request.getOriginatingBankCode().intValue() < 0) {
            throwValidationError(
                    "originatingBranchCode and originatingBankCode must be non-negative integer codes");
        }

        /*
         * The PL/SQL package validates only that a scope is present. PO01 must
         * bind it to the authenticated session before querying payment data.
         * Replace this only with the approved platform cross-scope helper.
         */
        if (request.getOriginatingBranchCode().intValue()
                != sessionContext.getTransactionBranch()
                || request.getOriginatingBankCode().intValue()
                != sessionContext.getBankCode()) {
            throwValidationError(
                    "Requested branch and bank must match the session scope");
        }

        if (hasExactReferenceInput(request)) {
            validateExactReferenceRequest(request);
        } else {
            validateDateListRequest(request);
        }
    }

    private void validateExactReferenceRequest(
            XfaceNEFTPaymentDiscoveryInquiryRequestDTO request)
            throws FatalException {

        String referenceType = request.getReferenceType();
        String reference = request.getReference();

        if (!"FCR".equals(referenceType) && !"UTR".equals(referenceType)) {
            throwValidationError("referenceType must be FCR or UTR");
        }
        if (isBlank(reference)) {
            throwValidationError(
                    "reference is required with referenceType for exact lookup");
        }
        if (!reference.equals(reference.trim())) {
            throwValidationError(
                    "reference must not contain leading or trailing spaces");
        }
        if (reference.length() > 200) {
            throwValidationError("reference must not exceed 200 characters");
        }
        if (request.getInquiryDate() != null
                || request.getRecordCount() != null) {
            throwValidationError(
                    "Exact reference lookup does not accept inquiryDate or recordCount");
        }
    }

    private void validateDateListRequest(
            XfaceNEFTPaymentDiscoveryInquiryRequestDTO request)
            throws FatalException {

        if (isBlank(request.getInquiryDate())
                || request.getRecordCount() == null) {
            throwValidationError(
                    "List lookup requires inquiryDate and recordCount");
        }
        if (request.getRecordCount().intValue() < 1
                || request.getRecordCount().intValue()
                > MAX_DISCOVERY_PAYMENT_GROUPS) {
            throwValidationError(
                    "recordCount must be an integer from 1 through 200");
        }
        toInquiryDate(request.getInquiryDate());
    }

    private boolean hasExactReferenceInput(
            XfaceNEFTPaymentDiscoveryInquiryRequestDTO request) {

        return request.getReferenceType() != null
                || request.getReference() != null;
    }

    private Date toInquiryDate(String inquiryDate) throws FatalException {

        if (inquiryDate == null) {
            return null;
        }

        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd");
        parser.setLenient(false);
        try {
            java.util.Date parsedDate = parser.parse(inquiryDate);
            if (!parser.format(parsedDate).equals(inquiryDate)) {
                throw new ParseException("Invalid inquiry date", 0);
            }
            return new Date(parsedDate.getTime());
        } catch (ParseException e) {
            throwValidationError(
                    "inquiryDate must be in strict yyyy-MM-dd format");
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    private void throwValidationError(String message) throws FatalException {
        ErrorManager.throwFatalException(FCRJErrorConstants.MID_DUMMY_ERROR_CODE,
                new String[] { message, "", "" }, null, null);
    }

    private LinkedHashMap<String, List<CursorApBaNeftDiscoveryInq_1>>
            groupByPaymentReference(
                    CursorApBaNeftDiscoveryInq_1[] cursorDetails)
                    throws FatalException {

        LinkedHashMap<String, List<CursorApBaNeftDiscoveryInq_1>>
                paymentGroups = new LinkedHashMap<String,
                        List<CursorApBaNeftDiscoveryInq_1>>();
        if (cursorDetails == null) {
            return paymentGroups;
        }

        for (int index = 0; index < cursorDetails.length; index++) {
            CursorApBaNeftDiscoveryInq_1 cursorDetail =
                    cursorDetails[index];
            if (cursorDetail == null || isBlank(cursorDetail.getRefTxnNo())) {
                throwValidationError(
                        "Discovery result contains a blank reference transaction number");
            }

            List<CursorApBaNeftDiscoveryInq_1> paymentRows =
                    paymentGroups.get(cursorDetail.getRefTxnNo());
            if (paymentRows == null) {
                paymentRows = new ArrayList<CursorApBaNeftDiscoveryInq_1>();
                paymentGroups.put(cursorDetail.getRefTxnNo(), paymentRows);
            }
            paymentRows.add(cursorDetail);
        }
        return paymentGroups;
    }

    private CursorApBaNeftDiscoveryInq_1[] retainPaymentGroups(
            LinkedHashMap<String, List<CursorApBaNeftDiscoveryInq_1>>
                    paymentGroups,
            int requestedPaymentGroups) {

        List<CursorApBaNeftDiscoveryInq_1> retainedRows =
                new ArrayList<CursorApBaNeftDiscoveryInq_1>();
        int groupCount = 0;
        for (Map.Entry<String, List<CursorApBaNeftDiscoveryInq_1>> entry :
                paymentGroups.entrySet()) {
            if (groupCount >= requestedPaymentGroups) {
                break;
            }
            retainedRows.addAll(entry.getValue());
            groupCount++;
        }
        return retainedRows.toArray(
                new CursorApBaNeftDiscoveryInq_1[retainedRows.size()]);
    }

    private NEFTPaymentDiscoveryDetailsDTO[] mapDiscoveryDetails(
            CursorApBaNeftDiscoveryInq_1[] cursorDetails) {

        if (cursorDetails == null) {
            return new NEFTPaymentDiscoveryDetailsDTO[0];
        }
        NEFTPaymentDiscoveryDetailsDTO[] details =
                new NEFTPaymentDiscoveryDetailsDTO[cursorDetails.length];
        for (int index = 0; index < cursorDetails.length; index++) {
            CursorApBaNeftDiscoveryInq_1 source = cursorDetails[index];
            NEFTPaymentDiscoveryDetailsDTO target =
                    new NEFTPaymentDiscoveryDetailsDTO();
            target.setReferenceTransactionNumber(source.getRefTxnNo());
            target.setOriginatingBranchCode(source.getOrgBrn());
            target.setOriginatingBankCode(source.getOrgBank());
            target.setReferenceSubsequenceNumber(source.getRefSubseqNo());
            target.setUtrReferenceNumber(source.getUtrRefNo());
            target.setInitiationDateTime(source.getDatInitiation());
            target.setTransactionAmount(source.getNumAmount4038());
            details[index] = target;
        }
        return details;
    }
}
