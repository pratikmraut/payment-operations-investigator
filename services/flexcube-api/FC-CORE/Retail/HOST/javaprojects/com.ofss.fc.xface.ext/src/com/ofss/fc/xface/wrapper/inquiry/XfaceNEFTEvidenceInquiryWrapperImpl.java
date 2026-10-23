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

package com.ofss.fc.xface.wrapper.inquiry;

import java.sql.SQLException;

import com.ofss.fc.app.Interaction;
import com.ofss.fc.app.context.SessionContext;
import com.ofss.fc.common.FCRJErrorConstants;
import com.ofss.fc.entity.transaction.TransactionStatus;
import com.ofss.fc.infra.error.ErrorManager;
import com.ofss.fc.infra.exception.FatalException;
import com.ofss.fc.infra.log.MessageLogger;
import com.ofss.fc.xface.common.AbstractXfaceApplication;
import com.ofss.fc.xface.db.procedure.impl.PkBaNEFTEvidenceInquiry;
import com.ofss.fc.xface.db.procedure.impl.PkBaNEFTEvidenceInquiry.CursorApBaNeftHistoryInq_1;
import com.ofss.fc.xface.db.procedure.impl.PkBaNEFTEvidenceInquiry.CursorApBaNeftHostInq_1;
import com.ofss.fc.xface.db.procedure.impl.PkBaNEFTEvidenceInquiry.CursorApBaNeftPaymentInq_1;
import com.ofss.fc.xface.db.procedure.impl.PkBaNEFTEvidenceInquiry.CursorApBaNeftStatusInq_1;
import com.ofss.fc.xface.dto.inquiry.NEFTHistoryEvidenceDetailsDTO;
import com.ofss.fc.xface.dto.inquiry.NEFTHostEvidenceDetailsDTO;
import com.ofss.fc.xface.dto.inquiry.NEFTPaymentEvidenceDetailsDTO;
import com.ofss.fc.xface.dto.inquiry.NEFTStatusEvidenceDetailsDTO;
import com.ofss.fc.xface.dto.inquiry.XfaceNEFTEvidenceInquiryRequestDTO;
import com.ofss.fc.xface.dto.inquiry.XfaceNEFTEvidenceInquiryResponseDTO;

/**
 * Aggregates the four evidence groups supplied by PK_BA_NEFT_EVIDENCE_INQ.
 * The package deliberately performs four separate statements, therefore this
 * response is an evidence bundle and does not claim a common database snapshot.
 */
public class XfaceNEFTEvidenceInquiryWrapperImpl
        extends AbstractXfaceApplication {

    public XfaceNEFTEvidenceInquiryResponseDTO processRequest(
            SessionContext sessionContext,
            XfaceNEFTEvidenceInquiryRequestDTO request)
            throws FatalException {

        final String methodName = "processRequest";
        XfaceNEFTEvidenceInquiryResponseDTO response =
                new XfaceNEFTEvidenceInquiryResponseDTO();
        TransactionStatus status = fetchTransactionStatus();

        try {
            Interaction.begin(sessionContext);
            validateRequest(sessionContext, request);
            response = fetchNeftEvidence(sessionContext, request);
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

    private XfaceNEFTEvidenceInquiryResponseDTO fetchNeftEvidence(
            SessionContext sessionContext,
            XfaceNEFTEvidenceInquiryRequestDTO request)
            throws FatalException {

        try {
            PkBaNEFTEvidenceInquiry storedProc =
                    new PkBaNEFTEvidenceInquiry();

            PkBaNEFTEvidenceInquiry.ReturnVOApBaNeftPaymentInq paymentReturn =
                    storedProc.execApBaNeftPaymentInq(
                            request.getReferenceTransactionNumber(),
                            request.getOriginatingBranchCode(),
                            request.getOriginatingBankCode());
            verifyReturnCode(paymentReturn.getReturnValue(), "payment");

            PkBaNEFTEvidenceInquiry.ReturnVOApBaNeftHostInq hostReturn =
                    storedProc.execApBaNeftHostInq(
                            request.getReferenceTransactionNumber(),
                            request.getOriginatingBranchCode(),
                            request.getOriginatingBankCode());
            verifyReturnCode(hostReturn.getReturnValue(), "host");

            PkBaNEFTEvidenceInquiry.ReturnVOApBaNeftHistoryInq historyReturn =
                    storedProc.execApBaNeftHistoryInq(
                            request.getReferenceTransactionNumber(),
                            request.getOriginatingBranchCode(),
                            request.getOriginatingBankCode());
            verifyReturnCode(historyReturn.getReturnValue(), "history");

            PkBaNEFTEvidenceInquiry.ReturnVOApBaNeftStatusInq statusReturn =
                    storedProc.execApBaNeftStatusInq(
                            request.getReferenceTransactionNumber(),
                            request.getOriginatingBranchCode(),
                            request.getOriginatingBankCode());
            verifyReturnCode(statusReturn.getReturnValue(), "status");

            XfaceNEFTEvidenceInquiryResponseDTO response =
                    new XfaceNEFTEvidenceInquiryResponseDTO();
            response.setNeftPaymentEvidenceDetails(
                    mapPaymentEvidence(paymentReturn.getCursor1()));
            response.setNeftHostEvidenceDetails(
                    mapHostEvidence(hostReturn.getCursor1()));
            response.setNeftHistoryEvidenceDetails(
                    mapHistoryEvidence(historyReturn.getCursor1()));
            response.setNeftStatusEvidenceDetails(
                    mapStatusEvidence(statusReturn.getCursor1()));
            return response;
        } catch (SQLException e) {
            ErrorManager.logException(sessionContext.getServiceCode(), e);
            ErrorManager.throwFatalException(
                    FCRJErrorConstants.MID_DUMMY_ERROR_CODE,
                    new String[] { "Unable to retrieve NEFT evidence details",
                            "", "" }, null, null);
            return null;
        }
    }

    private void verifyReturnCode(long returnValue, String evidenceGroup)
            throws FatalException {

        if (returnValue != 0) {
            ErrorManager.throwFatalException(
                    FCRJErrorConstants.MID_DUMMY_ERROR_CODE,
                    new String[] { "Unable to retrieve " + evidenceGroup
                            + " NEFT evidence details", "", "" }, null,
                    null);
        }
    }

    private void validateRequest(SessionContext sessionContext,
            XfaceNEFTEvidenceInquiryRequestDTO request) throws FatalException {

        if (request == null || isBlank(request.getReferenceTransactionNumber())) {
            throwValidationError("referenceTransactionNumber is required");
        }
        if (!request.getReferenceTransactionNumber().equals(
                request.getReferenceTransactionNumber().trim())) {
            throwValidationError(
                    "referenceTransactionNumber must not contain leading or trailing spaces");
        }
        if (request.getReferenceTransactionNumber().length() > 40) {
            throwValidationError(
                    "referenceTransactionNumber must not exceed 40 characters");
        }
        if (request.getOriginatingBranchCode() == null
                || request.getOriginatingBankCode() == null) {
            throwValidationError(
                    "originatingBranchCode and originatingBankCode are required");
        }

        /*
         * PO02 is intentionally scope-bound.  A caller must not obtain NEFT
         * evidence for a branch or bank different from its authenticated
         * SessionContext.  Any future centralized cross-scope entitlement must
         * replace this check with the approved platform authorization helper.
         */
        if (request.getOriginatingBranchCode().intValue()
                != sessionContext.getTransactionBranch()
                || request.getOriginatingBankCode().intValue()
                != sessionContext.getBankCode()) {
            throwValidationError(
                    "Requested branch and bank must match the session scope");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    private void throwValidationError(String message) throws FatalException {
        ErrorManager.throwFatalException(FCRJErrorConstants.MID_DUMMY_ERROR_CODE,
                new String[] { message, "", "" }, null, null);
    }

    private NEFTPaymentEvidenceDetailsDTO[] mapPaymentEvidence(
            CursorApBaNeftPaymentInq_1[] cursorDetails) {

        if (cursorDetails == null) {
            return new NEFTPaymentEvidenceDetailsDTO[0];
        }
        NEFTPaymentEvidenceDetailsDTO[] details =
                new NEFTPaymentEvidenceDetailsDTO[cursorDetails.length];
        for (int index = 0; index < cursorDetails.length; index++) {
            CursorApBaNeftPaymentInq_1 source = cursorDetails[index];
            NEFTPaymentEvidenceDetailsDTO target =
                    new NEFTPaymentEvidenceDetailsDTO();
            copyCommon(target, source.getSourceTable(),
                    source.getQueryObservedAt(), source.getScopeRowCount());
            target.setRefTxnNumber(source.getRefTxnNumber());
            target.setIdUserReference2020(source.getIdUserReference2020());
            target.setIdRelatedRef2006(source.getIdRelatedRef2006());
            target.setUtrRefNo(source.getUtrRefNo());
            target.setIdMsgReference2020(source.getIdMsgReference2020());
            target.setIdSequenceNo(source.getIdSequenceNo());
            target.setNumAmount4038(source.getNumAmount4038());
            target.setCodCurr(source.getCodCurr());
            target.setDirection(source.getDirection());
            target.setDatInitiation(source.getDatInitiation());
            target.setDatValue3380(source.getDatValue3380());
            target.setMsgType(source.getMsgType());
            target.setSubMsgType(source.getSubMsgType());
            target.setTxnType(source.getTxnType());
            target.setCodStatus(source.getCodStatus());
            target.setAcctStatus(source.getAcctStatus());
            target.setMsgStatus(source.getMsgStatus());
            target.setNumRetry(source.getNumRetry());
            target.setBatchTime3535(source.getBatchTime3535());
            target.setReasonCode6346(source.getReasonCode6346());
            target.setN10MsgId(source.getN10MsgId());
            target.setN10IdSequenceNo(source.getN10IdSequenceNo());
            target.setN10TxnId(source.getN10TxnId());
            target.setN10Status(source.getN10Status());
            target.setN10DateTime(source.getN10DateTime());
            target.setN10RecvSentDateTime(source.getN10RecvSentDateTime());
            details[index] = target;
        }
        return details;
    }

    private NEFTHostEvidenceDetailsDTO[] mapHostEvidence(
            CursorApBaNeftHostInq_1[] cursorDetails) {

        if (cursorDetails == null) {
            return new NEFTHostEvidenceDetailsDTO[0];
        }
        NEFTHostEvidenceDetailsDTO[] details =
                new NEFTHostEvidenceDetailsDTO[cursorDetails.length];
        for (int index = 0; index < cursorDetails.length; index++) {
            CursorApBaNeftHostInq_1 source = cursorDetails[index];
            NEFTHostEvidenceDetailsDTO target = new NEFTHostEvidenceDetailsDTO();
            copyCommon(target, source.getSourceTable(),
                    source.getQueryObservedAt(), source.getScopeRowCount());
            target.setRefTxnNo(source.getRefTxnNo());
            target.setRefSubseqNo(source.getRefSubseqNo());
            target.setRefNetworkNo(source.getRefNetworkNo());
            target.setRefUsrNo(source.getRefUsrNo());
            target.setCodOrgBrn(source.getCodOrgBrn());
            target.setCodOrgBank(source.getCodOrgBank());
            target.setCodNetworkId(source.getCodNetworkId());
            target.setCodPaymentTxn(source.getCodPaymentTxn());
            target.setPayDir(source.getPayDir());
            target.setCodChnlId(source.getCodChnlId());
            target.setAmtTxnTcy(source.getAmtTxnTcy());
            target.setCodTxnCcy(source.getCodTxnCcy());
            target.setDatTxn(source.getDatTxn());
            target.setDatInitiation(source.getDatInitiation());
            target.setDatPost(source.getDatPost());
            target.setDatValue(source.getDatValue());
            target.setDatActivation(source.getDatActivation());
            target.setDatDispatch(source.getDatDispatch());
            target.setDatNtwkValue(source.getDatNtwkValue());
            target.setDatAuthtime(source.getDatAuthtime());
            target.setDatSecondAuthtime(source.getDatSecondAuthtime());
            target.setTxnStat(source.getTxnStat());
            target.setAcctStat(source.getAcctStat());
            target.setMsgStat(source.getMsgStat());
            target.setNtwkAcctStat(source.getNtwkAcctStat());
            target.setContgAcctStat(source.getContgAcctStat());
            target.setCtPartyAcctStat(source.getCtPartyAcctStat());
            target.setFlgPostCutoff(source.getFlgPostCutoff());
            target.setCodReply(source.getCodReply());
            target.setCodExt(source.getCodExt());
            target.setCodReject(source.getCodReject());
            target.setSfmsRejCode(source.getSfmsRejCode());
            target.setRefTxnNoRev(source.getRefTxnNoRev());
            details[index] = target;
        }
        return details;
    }

    private NEFTHistoryEvidenceDetailsDTO[] mapHistoryEvidence(
            CursorApBaNeftHistoryInq_1[] cursorDetails) {

        if (cursorDetails == null) {
            return new NEFTHistoryEvidenceDetailsDTO[0];
        }
        NEFTHistoryEvidenceDetailsDTO[] details =
                new NEFTHistoryEvidenceDetailsDTO[cursorDetails.length];
        for (int index = 0; index < cursorDetails.length; index++) {
            CursorApBaNeftHistoryInq_1 source = cursorDetails[index];
            NEFTHistoryEvidenceDetailsDTO target =
                    new NEFTHistoryEvidenceDetailsDTO();
            copyCommon(target, source.getSourceTable(),
                    source.getQueryObservedAt(), source.getScopeRowCount());
            target.setRefTxnNo(source.getRefTxnNo());
            target.setCodOrgBrn(source.getCodOrgBrn());
            target.setCodOrgBank(source.getCodOrgBank());
            target.setDatTxn(source.getDatTxn());
            target.setDatPost(source.getDatPost());
            target.setDatValue(source.getDatValue());
            target.setAcctStat(source.getAcctStat());
            target.setMsgStat(source.getMsgStat());
            target.setTxnStat(source.getTxnStat());
            target.setNtwkAcctStat(source.getNtwkAcctStat());
            target.setContgAcctStat(source.getContgAcctStat());
            target.setCodChnlId(source.getCodChnlId());
            details[index] = target;
        }
        return details;
    }

    private NEFTStatusEvidenceDetailsDTO[] mapStatusEvidence(
            CursorApBaNeftStatusInq_1[] cursorDetails) {

        if (cursorDetails == null) {
            return new NEFTStatusEvidenceDetailsDTO[0];
        }
        NEFTStatusEvidenceDetailsDTO[] details =
                new NEFTStatusEvidenceDetailsDTO[cursorDetails.length];
        for (int index = 0; index < cursorDetails.length; index++) {
            CursorApBaNeftStatusInq_1 source = cursorDetails[index];
            NEFTStatusEvidenceDetailsDTO target =
                    new NEFTStatusEvidenceDetailsDTO();
            copyCommon(target, source.getSourceTable(),
                    source.getQueryObservedAt(), source.getScopeRowCount());
            target.setCodStatus(source.getCodStatus());
            target.setMsgStatus(source.getMsgStatus());
            target.setAcctStatus(source.getAcctStatus());
            target.setNeftCodStatus(source.getNeftCodStatus());
            details[index] = target;
        }
        return details;
    }

    private void copyCommon(
            com.ofss.fc.xface.dto.inquiry.NEFTEvidenceRecordDTO target,
            String sourceTable, String queryObservedAt, String scopeRowCount) {

        target.setSourceTable(sourceTable);
        target.setQueryObservedAt(queryObservedAt);
        target.setScopeRowCount(scopeRowCount);
    }
}
