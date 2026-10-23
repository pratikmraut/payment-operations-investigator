/*------------------------------------------------------------------------------
This source is part of the Oracle FLEXCUBE V.CO 4.5.0.0.0.0.0 Software System
and is copyrighted by Oracle Financial Services Software Limited.
All rights reserved. No part of this work may be reproduced, stored in a
retrieval system, adopted or transmitted in any form or by any means,
electronic, mechanical, photographic, graphic, optic recording or otherwise,
translated in any language or computer language, without the prior written
permission of Oracle Financial Services Software Limited.
Oracle Financial Services Software Limited.
10-11, SDF I, SEEPZ, Andheri (East), Mumbai - 400 096, India.
Copyright c [2010] Oracle Financial Services Software Limited.
      $Revision$
      $LastChangedDate$
      $LastChangedBy$
      $HeadURL$
      $Id$
-------------------------------------------------------------------------------
Modification History
Date        Version     Author          Description
----------  -----------  --------------  ---------------------------------------
14/09/2026  1.1          pmraut          Standardized package structure; public contract and query behavior retained.
-------------------------------------------------------------------------------*/
CREATE OR REPLACE PACKAGE PK_BA_NEFT_EVIDENCE_INQ
AUTHID CURRENT_USER
AS

  -- Public contract. Keep types, signatures, and cursor column order stable.
  SUBTYPE T_EXPORT_TEXT IS VARCHAR2(4000);

  TYPE T_PAYMENT_RECORD IS RECORD(
    SOURCE_TABLE                T_EXPORT_TEXT,
    QUERY_OBSERVED_AT           T_EXPORT_TEXT,
    SCOPE_ROW_COUNT             T_EXPORT_TEXT,
    REFTXNNUMBER                T_EXPORT_TEXT,
    IDUSERREFERENCE_2020        T_EXPORT_TEXT,
    IDRELATEDREF_2006           T_EXPORT_TEXT,
    UTR_REF_NO                  T_EXPORT_TEXT,
    IDMSGREFERENCE_2020         T_EXPORT_TEXT,
    IDSEQUENCENO                T_EXPORT_TEXT,
    NUMAMOUNT_4038              T_EXPORT_TEXT,
    CODCURR                     T_EXPORT_TEXT,
    DIRECTION                   T_EXPORT_TEXT,
    DATINITIATION               T_EXPORT_TEXT,
    DATVALUE_3380               T_EXPORT_TEXT,
    MSGTYPE                     T_EXPORT_TEXT,
    SUBMSGTYPE                  T_EXPORT_TEXT,
    TXNTYPE                     T_EXPORT_TEXT,
    CODSTATUS                   T_EXPORT_TEXT,
    ACCTSTATUS                  T_EXPORT_TEXT,
    MSGSTATUS                   T_EXPORT_TEXT,
    NUMRETRY                    T_EXPORT_TEXT,
    BATCH_TIME_3535             T_EXPORT_TEXT,
    REASONCODE_6346             T_EXPORT_TEXT,
    N10_MSGID                   T_EXPORT_TEXT,
    N10_IDSEQUENCENO            T_EXPORT_TEXT,
    N10_TXNID                   T_EXPORT_TEXT,
    N10_STATUS                  T_EXPORT_TEXT,
    N10_DATETIME                T_EXPORT_TEXT,
    N10_RECV_SENT_DATETIME      T_EXPORT_TEXT);

  TYPE T_PAYMENT_CURSOR IS REF CURSOR RETURN T_PAYMENT_RECORD;

  FUNCTION AP_BA_NEFT_PAYMENT_INQ(
    PIO_REF_TXN_NO IN OUT PM_NEFT_TXN_LOG.REFTXNNUMBER%TYPE,
    PIO_ORG_BRN    IN OUT PM_TXN_LOG.COD_ORG_BRN%TYPE,
    PIO_ORG_BANK   IN OUT PM_TXN_LOG.COD_ORG_BANK%TYPE,
    PIO_RESULT     IN OUT T_PAYMENT_CURSOR)
    RETURN NUMBER;

  TYPE T_HOST_RECORD IS RECORD(
    SOURCE_TABLE                T_EXPORT_TEXT,
    QUERY_OBSERVED_AT           T_EXPORT_TEXT,
    SCOPE_ROW_COUNT             T_EXPORT_TEXT,
    REF_TXN_NO                  T_EXPORT_TEXT,
    REF_SUBSEQ_NO               T_EXPORT_TEXT,
    REF_NETWORK_NO              T_EXPORT_TEXT,
    REF_USR_NO                  T_EXPORT_TEXT,
    COD_ORG_BRN                 T_EXPORT_TEXT,
    COD_ORG_BANK                T_EXPORT_TEXT,
    COD_NETWORK_ID              T_EXPORT_TEXT,
    COD_PAYMENT_TXN             T_EXPORT_TEXT,
    PAY_DIR                     T_EXPORT_TEXT,
    COD_CHNL_ID                 T_EXPORT_TEXT,
    AMT_TXN_TCY                 T_EXPORT_TEXT,
    COD_TXN_CCY                 T_EXPORT_TEXT,
    DAT_TXN                     T_EXPORT_TEXT,
    DAT_INITIATION              T_EXPORT_TEXT,
    DAT_POST                    T_EXPORT_TEXT,
    DAT_VALUE                   T_EXPORT_TEXT,
    DAT_ACTIVATION              T_EXPORT_TEXT,
    DAT_DISPATCH                T_EXPORT_TEXT,
    DAT_NTWK_VALUE              T_EXPORT_TEXT,
    DAT_AUTHTIME                T_EXPORT_TEXT,
    DAT_SECOND_AUTHTIME         T_EXPORT_TEXT,
    TXN_STAT                    T_EXPORT_TEXT,
    ACCT_STAT                   T_EXPORT_TEXT,
    MSG_STAT                    T_EXPORT_TEXT,
    NTWK_ACCT_STAT              T_EXPORT_TEXT,
    CONTG_ACCT_STAT             T_EXPORT_TEXT,
    CT_PARTY_ACCT_STAT          T_EXPORT_TEXT,
    FLG_POST_CUTOFF             T_EXPORT_TEXT,
    COD_REPLY                   T_EXPORT_TEXT,
    COD_EXT                     T_EXPORT_TEXT,
    COD_REJECT                  T_EXPORT_TEXT,
    SFMS_REJ_CODE               T_EXPORT_TEXT,
    REF_TXN_NO_REV              T_EXPORT_TEXT);

  TYPE T_HOST_CURSOR IS REF CURSOR RETURN T_HOST_RECORD;

  FUNCTION AP_BA_NEFT_HOST_INQ(
    PIO_REF_TXN_NO IN OUT PM_NEFT_TXN_LOG.REFTXNNUMBER%TYPE,
    PIO_ORG_BRN    IN OUT PM_TXN_LOG.COD_ORG_BRN%TYPE,
    PIO_ORG_BANK   IN OUT PM_TXN_LOG.COD_ORG_BANK%TYPE,
    PIO_RESULT     IN OUT T_HOST_CURSOR)
    RETURN NUMBER;

  TYPE T_HISTORY_RECORD IS RECORD(
    SOURCE_TABLE                T_EXPORT_TEXT,
    QUERY_OBSERVED_AT           T_EXPORT_TEXT,
    SCOPE_ROW_COUNT             T_EXPORT_TEXT,
    REF_TXN_NO                  T_EXPORT_TEXT,
    COD_ORG_BRN                 T_EXPORT_TEXT,
    COD_ORG_BANK                T_EXPORT_TEXT,
    DAT_TXN                     T_EXPORT_TEXT,
    DAT_POST                    T_EXPORT_TEXT,
    DAT_VALUE                   T_EXPORT_TEXT,
    ACCT_STAT                   T_EXPORT_TEXT,
    MSG_STAT                    T_EXPORT_TEXT,
    TXN_STAT                    T_EXPORT_TEXT,
    NTWK_ACCT_STAT              T_EXPORT_TEXT,
    CONTG_ACCT_STAT             T_EXPORT_TEXT,
    COD_CHNL_ID                 T_EXPORT_TEXT);

  TYPE T_HISTORY_CURSOR IS REF CURSOR RETURN T_HISTORY_RECORD;

  FUNCTION AP_BA_NEFT_HISTORY_INQ(
    PIO_REF_TXN_NO IN OUT PM_NEFT_TXN_LOG.REFTXNNUMBER%TYPE,
    PIO_ORG_BRN    IN OUT PM_TXN_LOG.COD_ORG_BRN%TYPE,
    PIO_ORG_BANK   IN OUT PM_TXN_LOG.COD_ORG_BANK%TYPE,
    PIO_RESULT     IN OUT T_HISTORY_CURSOR)
    RETURN NUMBER;

  TYPE T_STATUS_RECORD IS RECORD(
    SOURCE_TABLE                T_EXPORT_TEXT,
    QUERY_OBSERVED_AT           T_EXPORT_TEXT,
    SCOPE_ROW_COUNT             T_EXPORT_TEXT,
    CODSTATUS                   T_EXPORT_TEXT,
    MSGSTATUS                   T_EXPORT_TEXT,
    ACCTSTATUS                  T_EXPORT_TEXT,
    NEFTCODSTATUS               T_EXPORT_TEXT);

  TYPE T_STATUS_CURSOR IS REF CURSOR RETURN T_STATUS_RECORD;

  FUNCTION AP_BA_NEFT_STATUS_INQ(
    PIO_REF_TXN_NO IN OUT PM_NEFT_TXN_LOG.REFTXNNUMBER%TYPE,
    PIO_ORG_BRN    IN OUT PM_TXN_LOG.COD_ORG_BRN%TYPE,
    PIO_ORG_BANK   IN OUT PM_TXN_LOG.COD_ORG_BANK%TYPE,
    PIO_RESULT     IN OUT T_STATUS_CURSOR)
    RETURN NUMBER;

  -- Discovery rows for Reference / UTR and Inquiry API (not detailed evidence).
  TYPE T_DISCOVERY_RECORD IS RECORD(
    PIO_REF_TXN_NO  T_EXPORT_TEXT,
    PIO_ORG_BRN     T_EXPORT_TEXT,
    PIO_ORG_BANK    T_EXPORT_TEXT,
    REF_SUBSEQ_NO  T_EXPORT_TEXT,
    UTR_REF_NO     T_EXPORT_TEXT,
    DATINITIATION  T_EXPORT_TEXT,
    NUMAMOUNT_4038 T_EXPORT_TEXT);

  TYPE T_DISCOVERY_CURSOR IS REF CURSOR RETURN T_DISCOVERY_RECORD;

  -- Exact: reference type FCR/UTR + reference; date/count must be NULL.
  -- List: date + count 1..200; both reference inputs must be NULL.
  -- List returns up to count+1 distinct payment groups for wrapper look-ahead.
  -- Exact returns up to 201 groups: wrapper must reject >200, never truncate.
  -- Every retained payment includes its matching native rows/host subsequences.
  -- Wrapper must fetch successfully, apply limits, serialize, and close cursor.
  FUNCTION AP_BA_NEFT_DISCOVERY_INQ(
    PIO_ORG_BRN       IN OUT PM_TXN_LOG.COD_ORG_BRN%TYPE,
    PIO_ORG_BANK      IN OUT PM_TXN_LOG.COD_ORG_BANK%TYPE,
    PIO_RESULT       IN OUT T_DISCOVERY_CURSOR,
    PIO_OBSERVED_AT   OUT T_EXPORT_TEXT,
    PI_REFERENCE_TYPE IN VARCHAR2 DEFAULT NULL,
    PI_REFERENCE      IN VARCHAR2 DEFAULT NULL,
    PI_INQUIRY_DATE   IN DATE DEFAULT NULL,
    PI_RECORD_COUNT   IN NUMBER DEFAULT NULL)
    RETURN NUMBER;


END PK_BA_NEFT_EVIDENCE_INQ;
/

CREATE OR REPLACE PACKAGE BODY PK_BA_NEFT_EVIDENCE_INQ
AS

  C_ERROR_LOG_POSITION CONSTANT PLS_INTEGER := 10;

  -- Private validation and error-handling routines.
  PROCEDURE ASSERT_SCOPE(
    P_REF_TXN_NO IN PM_NEFT_TXN_LOG.REFTXNNUMBER%TYPE,
    P_ORG_BRN IN PM_TXN_LOG.COD_ORG_BRN%TYPE,
    P_ORG_BANK IN PM_TXN_LOG.COD_ORG_BANK%TYPE) IS
  BEGIN
    -- The API wrapper must independently authorize this branch/bank scope.
    -- Do not silently trim or replace an operational identity supplied by the caller.
    IF P_REF_TXN_NO IS NULL OR TRIM(P_REF_TXN_NO) IS NULL
       OR P_ORG_BRN IS NULL OR P_ORG_BANK IS NULL THEN
      RAISE_APPLICATION_ERROR(-20001, 'Payment reference, branch and bank are required.');
    END IF;
  END ASSERT_SCOPE;

  PROCEDURE LOG_ERROR(P_ERROR_CODE IN NUMBER, P_CONTEXT IN VARCHAR2) IS
  BEGIN
    -- Site-specific procedure signature observed in the supplied template.
    -- Its implementation/privileges and possible logging effects are not verified.
    ORA_RAISERROR(P_ERROR_CODE, P_CONTEXT, C_ERROR_LOG_POSITION);
  EXCEPTION
    WHEN OTHERS THEN
      -- Logging must not replace the inquiry's original nonzero return code.
      NULL;
  END LOG_ERROR;

  -- Public evidence inquiry functions.
  FUNCTION AP_BA_NEFT_PAYMENT_INQ(
    PIO_REF_TXN_NO IN OUT PM_NEFT_TXN_LOG.REFTXNNUMBER%TYPE,
    PIO_ORG_BRN    IN OUT PM_TXN_LOG.COD_ORG_BRN%TYPE,
    PIO_ORG_BANK   IN OUT PM_TXN_LOG.COD_ORG_BANK%TYPE,
    PIO_RESULT     IN OUT T_PAYMENT_CURSOR)
    RETURN NUMBER AS
    L_ERROR_CODE NUMBER;
    L_CURSOR_OPENED BOOLEAN := FALSE;
  BEGIN
    ASSERT_SCOPE(PIO_REF_TXN_NO, PIO_ORG_BRN, PIO_ORG_BANK);
    IF PIO_RESULT%ISOPEN THEN
      RAISE_APPLICATION_ERROR(-20002, 'Output cursor must be closed before inquiry.');
    END IF;

    -- FCR_Payment: all matching rows. Caller owns complete fetch and cursor closure.
    -- Separate function calls are separate statement observations, not a common SCN.
    OPEN PIO_RESULT FOR
      SELECT 'PM_NEFT_TXN_LOG' AS SOURCE_TABLE,
             TO_CHAR(SYSTIMESTAMP, 'YYYY-MM-DD"T"HH24:MI:SS.FF6TZH:TZM', 'NLS_DATE_LANGUAGE=English') AS QUERY_OBSERVED_AT,
             TO_CHAR(COUNT(*) OVER (), 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS SCOPE_ROW_COUNT,
             CAST(t.REFTXNNUMBER AS VARCHAR2(4000)) AS REFTXNNUMBER,
             CAST(t.IDUSERREFERENCE_2020 AS VARCHAR2(4000)) AS IDUSERREFERENCE_2020,
             CAST(t.IDRELATEDREF_2006 AS VARCHAR2(4000)) AS IDRELATEDREF_2006,
             CAST(t.UTR_REF_NO AS VARCHAR2(4000)) AS UTR_REF_NO,
             CAST(t.IDMSGREFERENCE_2020 AS VARCHAR2(4000)) AS IDMSGREFERENCE_2020,
             CAST(t.IDSEQUENCENO AS VARCHAR2(4000)) AS IDSEQUENCENO,
             TO_CHAR(t.NUMAMOUNT_4038, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS NUMAMOUNT_4038,
             CAST(t.CODCURR AS VARCHAR2(4000)) AS CODCURR,
             CAST(t.DIRECTION AS VARCHAR2(4000)) AS DIRECTION,
             TO_CHAR(t.DATINITIATION, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DATINITIATION,
             TO_CHAR(t.DATVALUE_3380, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DATVALUE_3380,
             CAST(t.MSGTYPE AS VARCHAR2(4000)) AS MSGTYPE,
             CAST(t.SUBMSGTYPE AS VARCHAR2(4000)) AS SUBMSGTYPE,
             CAST(t.TXNTYPE AS VARCHAR2(4000)) AS TXNTYPE,
             TO_CHAR(t.CODSTATUS, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS CODSTATUS,
             TO_CHAR(t.ACCTSTATUS, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS ACCTSTATUS,
             TO_CHAR(t.MSGSTATUS, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS MSGSTATUS,
             TO_CHAR(t.NUMRETRY, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS NUMRETRY,
             CAST(t.BATCH_TIME_3535 AS VARCHAR2(4000)) AS BATCH_TIME_3535,
             CAST(t.REASONCODE_6346 AS VARCHAR2(4000)) AS REASONCODE_6346,
             CAST(t.N10_MSGID AS VARCHAR2(4000)) AS N10_MSGID,
             CAST(t.N10_IDSEQUENCENO AS VARCHAR2(4000)) AS N10_IDSEQUENCENO,
             CAST(t.N10_TXNID AS VARCHAR2(4000)) AS N10_TXNID,
             CAST(t.N10_STATUS AS VARCHAR2(4000)) AS N10_STATUS,
             TO_CHAR(t.N10_DATETIME, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS N10_DATETIME,
             TO_CHAR(t.N10_RECV_SENT_DATETIME, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS N10_RECV_SENT_DATETIME
      FROM PM_NEFT_TXN_LOG t
      WHERE t.REFTXNNUMBER = PIO_REF_TXN_NO
        AND EXISTS (
          SELECT 1
          FROM PM_TXN_LOG h
          WHERE h.REF_TXN_NO = t.REFTXNNUMBER
            AND h.REF_TXN_NO = PIO_REF_TXN_NO
            AND h.COD_ORG_BRN = PIO_ORG_BRN
            AND h.COD_ORG_BANK = PIO_ORG_BANK
        )
      ORDER BY t.REFTXNNUMBER;
    L_CURSOR_OPENED := TRUE;
    RETURN 0;
  EXCEPTION
    WHEN OTHERS THEN
      L_ERROR_CODE := SQLCODE;
      IF L_CURSOR_OPENED THEN
        BEGIN
          IF PIO_RESULT%ISOPEN THEN
            CLOSE PIO_RESULT;
          END IF;
        EXCEPTION
          WHEN OTHERS THEN NULL;
        END;
      END IF;
      LOG_ERROR(L_ERROR_CODE,
        'PK_BA_NEFT_EVIDENCE_INQ.AP_BA_NEFT_PAYMENT_INQ');
      RETURN L_ERROR_CODE;
  END AP_BA_NEFT_PAYMENT_INQ;

  FUNCTION AP_BA_NEFT_HOST_INQ(
    PIO_REF_TXN_NO IN OUT PM_NEFT_TXN_LOG.REFTXNNUMBER%TYPE,
    PIO_ORG_BRN    IN OUT PM_TXN_LOG.COD_ORG_BRN%TYPE,
    PIO_ORG_BANK   IN OUT PM_TXN_LOG.COD_ORG_BANK%TYPE,
    PIO_RESULT     IN OUT T_HOST_CURSOR)
    RETURN NUMBER AS
    L_ERROR_CODE NUMBER;
    L_CURSOR_OPENED BOOLEAN := FALSE;
  BEGIN
    ASSERT_SCOPE(PIO_REF_TXN_NO, PIO_ORG_BRN, PIO_ORG_BANK);
    IF PIO_RESULT%ISOPEN THEN
      RAISE_APPLICATION_ERROR(-20002, 'Output cursor must be closed before inquiry.');
    END IF;

    -- FCR_Host: all matching rows. Caller owns complete fetch and cursor closure.
    -- Separate function calls are separate statement observations, not a common SCN.
    OPEN PIO_RESULT FOR
      SELECT 'PM_TXN_LOG' AS SOURCE_TABLE,
             TO_CHAR(SYSTIMESTAMP, 'YYYY-MM-DD"T"HH24:MI:SS.FF6TZH:TZM', 'NLS_DATE_LANGUAGE=English') AS QUERY_OBSERVED_AT,
             TO_CHAR(COUNT(*) OVER (), 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS SCOPE_ROW_COUNT,
             CAST(t.REF_TXN_NO AS VARCHAR2(4000)) AS REF_TXN_NO,
             TO_CHAR(t.REF_SUBSEQ_NO, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS REF_SUBSEQ_NO,
             CAST(t.REF_NETWORK_NO AS VARCHAR2(4000)) AS REF_NETWORK_NO,
             CAST(t.REF_USR_NO AS VARCHAR2(4000)) AS REF_USR_NO,
             TO_CHAR(t.COD_ORG_BRN, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS COD_ORG_BRN,
             TO_CHAR(t.COD_ORG_BANK, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS COD_ORG_BANK,
             CAST(t.COD_NETWORK_ID AS VARCHAR2(4000)) AS COD_NETWORK_ID,
             CAST(t.COD_PAYMENT_TXN AS VARCHAR2(4000)) AS COD_PAYMENT_TXN,
             CAST(t.PAY_DIR AS VARCHAR2(4000)) AS PAY_DIR,
             CAST(t.COD_CHNL_ID AS VARCHAR2(4000)) AS COD_CHNL_ID,
             TO_CHAR(t.AMT_TXN_TCY, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS AMT_TXN_TCY,
             TO_CHAR(t.COD_TXN_CCY, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS COD_TXN_CCY,
             TO_CHAR(t.DAT_TXN, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_TXN,
             TO_CHAR(t.DAT_INITIATION, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_INITIATION,
             TO_CHAR(t.DAT_POST, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_POST,
             TO_CHAR(t.DAT_VALUE, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_VALUE,
             TO_CHAR(t.DAT_ACTIVATION, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_ACTIVATION,
             TO_CHAR(t.DAT_DISPATCH, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_DISPATCH,
             TO_CHAR(t.DAT_NTWK_VALUE, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_NTWK_VALUE,
             TO_CHAR(t.DAT_AUTHTIME, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_AUTHTIME,
             TO_CHAR(t.DAT_SECOND_AUTHTIME, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_SECOND_AUTHTIME,
             TO_CHAR(t.TXN_STAT, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS TXN_STAT,
             TO_CHAR(t.ACCT_STAT, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS ACCT_STAT,
             TO_CHAR(t.MSG_STAT, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS MSG_STAT,
             TO_CHAR(t.NTWK_ACCT_STAT, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS NTWK_ACCT_STAT,
             TO_CHAR(t.CONTG_ACCT_STAT, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS CONTG_ACCT_STAT,
             TO_CHAR(t.CT_PARTY_ACCT_STAT, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS CT_PARTY_ACCT_STAT,
             CAST(t.FLG_POST_CUTOFF AS VARCHAR2(4000)) AS FLG_POST_CUTOFF,
             TO_CHAR(t.COD_REPLY, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS COD_REPLY,
             CAST(t.COD_EXT AS VARCHAR2(4000)) AS COD_EXT,
             CAST(t.COD_REJECT AS VARCHAR2(4000)) AS COD_REJECT,
             CAST(t.SFMS_REJ_CODE AS VARCHAR2(4000)) AS SFMS_REJ_CODE,
             CAST(t.REF_TXN_NO_REV AS VARCHAR2(4000)) AS REF_TXN_NO_REV
      FROM PM_TXN_LOG t
      WHERE t.REF_TXN_NO = PIO_REF_TXN_NO
        AND t.COD_ORG_BRN = PIO_ORG_BRN
        AND t.COD_ORG_BANK = PIO_ORG_BANK
        AND EXISTS (
          SELECT 1 FROM PM_NEFT_TXN_LOG p
          WHERE p.REFTXNNUMBER = t.REF_TXN_NO
        )
      ORDER BY t.REF_TXN_NO, t.REF_SUBSEQ_NO;
    L_CURSOR_OPENED := TRUE;
    RETURN 0;
  EXCEPTION
    WHEN OTHERS THEN
      L_ERROR_CODE := SQLCODE;
      IF L_CURSOR_OPENED THEN
        BEGIN
          IF PIO_RESULT%ISOPEN THEN
            CLOSE PIO_RESULT;
          END IF;
        EXCEPTION
          WHEN OTHERS THEN NULL;
        END;
      END IF;
      LOG_ERROR(L_ERROR_CODE,
        'PK_BA_NEFT_EVIDENCE_INQ.AP_BA_NEFT_HOST_INQ');
      RETURN L_ERROR_CODE;
  END AP_BA_NEFT_HOST_INQ;

  FUNCTION AP_BA_NEFT_HISTORY_INQ(
    PIO_REF_TXN_NO IN OUT PM_NEFT_TXN_LOG.REFTXNNUMBER%TYPE,
    PIO_ORG_BRN    IN OUT PM_TXN_LOG.COD_ORG_BRN%TYPE,
    PIO_ORG_BANK   IN OUT PM_TXN_LOG.COD_ORG_BANK%TYPE,
    PIO_RESULT     IN OUT T_HISTORY_CURSOR)
    RETURN NUMBER AS
    L_ERROR_CODE NUMBER;
    L_CURSOR_OPENED BOOLEAN := FALSE;
  BEGIN
    ASSERT_SCOPE(PIO_REF_TXN_NO, PIO_ORG_BRN, PIO_ORG_BANK);
    IF PIO_RESULT%ISOPEN THEN
      RAISE_APPLICATION_ERROR(-20002, 'Output cursor must be closed before inquiry.');
    END IF;

    -- FCR_History: all matching rows. Caller owns complete fetch and cursor closure.
    -- Separate function calls are separate statement observations, not a common SCN.
    OPEN PIO_RESULT FOR
      SELECT 'PM_TXN_LOG_HIST' AS SOURCE_TABLE,
             TO_CHAR(SYSTIMESTAMP, 'YYYY-MM-DD"T"HH24:MI:SS.FF6TZH:TZM', 'NLS_DATE_LANGUAGE=English') AS QUERY_OBSERVED_AT,
             TO_CHAR(COUNT(*) OVER (), 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS SCOPE_ROW_COUNT,
             CAST(t.REF_TXN_NO AS VARCHAR2(4000)) AS REF_TXN_NO,
             TO_CHAR(t.COD_ORG_BRN, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS COD_ORG_BRN,
             TO_CHAR(t.COD_ORG_BANK, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS COD_ORG_BANK,
             TO_CHAR(t.DAT_TXN, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_TXN,
             TO_CHAR(t.DAT_POST, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_POST,
             TO_CHAR(t.DAT_VALUE, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DAT_VALUE,
             TO_CHAR(t.ACCT_STAT, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS ACCT_STAT,
             TO_CHAR(t.MSG_STAT, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS MSG_STAT,
             TO_CHAR(t.TXN_STAT, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS TXN_STAT,
             TO_CHAR(t.NTWK_ACCT_STAT, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS NTWK_ACCT_STAT,
             TO_CHAR(t.CONTG_ACCT_STAT, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS CONTG_ACCT_STAT,
             CAST(t.COD_CHNL_ID AS VARCHAR2(4000)) AS COD_CHNL_ID
      FROM PM_TXN_LOG_HIST t
      WHERE t.REF_TXN_NO = PIO_REF_TXN_NO
        AND t.COD_ORG_BRN = PIO_ORG_BRN
        AND t.COD_ORG_BANK = PIO_ORG_BANK
        AND EXISTS (
          SELECT 1 FROM PM_NEFT_TXN_LOG p
          WHERE p.REFTXNNUMBER = t.REF_TXN_NO
        )
      ORDER BY t.DAT_TXN, t.DAT_POST, t.DAT_VALUE;
    L_CURSOR_OPENED := TRUE;
    RETURN 0;
  EXCEPTION
    WHEN OTHERS THEN
      L_ERROR_CODE := SQLCODE;
      IF L_CURSOR_OPENED THEN
        BEGIN
          IF PIO_RESULT%ISOPEN THEN
            CLOSE PIO_RESULT;
          END IF;
        EXCEPTION
          WHEN OTHERS THEN NULL;
        END;
      END IF;
      LOG_ERROR(L_ERROR_CODE,
        'PK_BA_NEFT_EVIDENCE_INQ.AP_BA_NEFT_HISTORY_INQ');
      RETURN L_ERROR_CODE;
  END AP_BA_NEFT_HISTORY_INQ;

  FUNCTION AP_BA_NEFT_STATUS_INQ(
    PIO_REF_TXN_NO IN OUT PM_NEFT_TXN_LOG.REFTXNNUMBER%TYPE,
    PIO_ORG_BRN    IN OUT PM_TXN_LOG.COD_ORG_BRN%TYPE,
    PIO_ORG_BANK   IN OUT PM_TXN_LOG.COD_ORG_BANK%TYPE,
    PIO_RESULT     IN OUT T_STATUS_CURSOR)
    RETURN NUMBER AS
    L_ERROR_CODE NUMBER;
    L_CURSOR_OPENED BOOLEAN := FALSE;
  BEGIN
    ASSERT_SCOPE(PIO_REF_TXN_NO, PIO_ORG_BRN, PIO_ORG_BANK);
    IF PIO_RESULT%ISOPEN THEN
      RAISE_APPLICATION_ERROR(-20002, 'Output cursor must be closed before inquiry.');
    END IF;

    -- FCR_Status: all matching rows. Caller owns complete fetch and cursor closure.
    -- Separate function calls are separate statement observations, not a common SCN.
    OPEN PIO_RESULT FOR
      SELECT 'NEFTTXNCODSTATUS' AS SOURCE_TABLE,
             TO_CHAR(SYSTIMESTAMP, 'YYYY-MM-DD"T"HH24:MI:SS.FF6TZH:TZM', 'NLS_DATE_LANGUAGE=English') AS QUERY_OBSERVED_AT,
             TO_CHAR(COUNT(*) OVER (), 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS SCOPE_ROW_COUNT,
             TO_CHAR(t.CODSTATUS, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS CODSTATUS,
             TO_CHAR(t.MSGSTATUS, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS MSGSTATUS,
             TO_CHAR(t.ACCTSTATUS, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS ACCTSTATUS,
             CAST(t.NEFTCODSTATUS AS VARCHAR2(4000)) AS NEFTCODSTATUS
      FROM NEFTTXNCODSTATUS t
      WHERE EXISTS (
          SELECT 1 FROM PM_NEFT_TXN_LOG p
          WHERE p.REFTXNNUMBER = PIO_REF_TXN_NO
            AND (t.CODSTATUS = p.CODSTATUS OR (t.CODSTATUS IS NULL AND p.CODSTATUS IS NULL))
            AND (t.MSGSTATUS = p.MSGSTATUS OR (t.MSGSTATUS IS NULL AND p.MSGSTATUS IS NULL))
            AND (t.ACCTSTATUS = p.ACCTSTATUS OR (t.ACCTSTATUS IS NULL AND p.ACCTSTATUS IS NULL))
            AND EXISTS (
              SELECT 1 FROM PM_TXN_LOG h
              WHERE h.REF_TXN_NO = p.REFTXNNUMBER
                AND h.REF_TXN_NO = PIO_REF_TXN_NO
            AND h.COD_ORG_BRN = PIO_ORG_BRN
            AND h.COD_ORG_BANK = PIO_ORG_BANK
            )
        )
      ORDER BY t.CODSTATUS, t.MSGSTATUS, t.ACCTSTATUS, t.NEFTCODSTATUS;
    L_CURSOR_OPENED := TRUE;
    RETURN 0;
  EXCEPTION
    WHEN OTHERS THEN
      L_ERROR_CODE := SQLCODE;
      IF L_CURSOR_OPENED THEN
        BEGIN
          IF PIO_RESULT%ISOPEN THEN
            CLOSE PIO_RESULT;
          END IF;
        EXCEPTION
          WHEN OTHERS THEN NULL;
        END;
      END IF;
      LOG_ERROR(L_ERROR_CODE,
        'PK_BA_NEFT_EVIDENCE_INQ.AP_BA_NEFT_STATUS_INQ');
      RETURN L_ERROR_CODE;
  END AP_BA_NEFT_STATUS_INQ;

  FUNCTION AP_BA_NEFT_DISCOVERY_INQ(
    PIO_ORG_BRN       IN OUT PM_TXN_LOG.COD_ORG_BRN%TYPE,
    PIO_ORG_BANK      IN OUT PM_TXN_LOG.COD_ORG_BANK%TYPE,
    PIO_RESULT       IN OUT T_DISCOVERY_CURSOR,
    PIO_OBSERVED_AT   OUT T_EXPORT_TEXT,
    PI_REFERENCE_TYPE IN VARCHAR2 DEFAULT NULL,
    PI_REFERENCE      IN VARCHAR2 DEFAULT NULL,
    PI_INQUIRY_DATE   IN DATE DEFAULT NULL,
    PI_RECORD_COUNT   IN NUMBER DEFAULT NULL)
    RETURN NUMBER AS
    L_ERROR_CODE NUMBER;
    L_CURSOR_OPENED BOOLEAN := FALSE;
    L_MODE VARCHAR2(4);
    L_PAYMENT_LIMIT PLS_INTEGER;
    L_DAY_START DATE;
    L_DAY_END DATE;
    L_FCR_REFERENCE PM_NEFT_TXN_LOG.REFTXNNUMBER%TYPE;
    L_UTR_REFERENCE PM_NEFT_TXN_LOG.UTR_REF_NO%TYPE;
  BEGIN
    PIO_OBSERVED_AT := NULL;
    -- Bank/branch authorization belongs to the authenticated API wrapper.
    -- Invoker rights and non-null scope values alone do not authorize a caller.
    IF PIO_ORG_BRN IS NULL OR PIO_ORG_BANK IS NULL THEN
      RAISE_APPLICATION_ERROR(-20020, 'Bank and branch are required.');
    END IF;
    IF NOT REGEXP_LIKE(TO_CHAR(PIO_ORG_BRN, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,'''), '^[0-9]{1,10}$')
       OR NOT REGEXP_LIKE(TO_CHAR(PIO_ORG_BANK, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,'''), '^[0-9]{1,10}$') THEN
      RAISE_APPLICATION_ERROR(-20020, 'Bank and branch must be integer codes of 1 through 10 digits.');
    END IF;
    IF PIO_RESULT%ISOPEN THEN
      RAISE_APPLICATION_ERROR(-20002, 'Output cursor must be closed before inquiry.');
    END IF;

    IF PI_REFERENCE_TYPE IS NOT NULL OR PI_REFERENCE IS NOT NULL THEN
      IF PI_REFERENCE_TYPE IS NULL OR PI_REFERENCE IS NULL
         OR PI_REFERENCE_TYPE NOT IN ('FCR', 'UTR')
         OR TRIM(PI_REFERENCE) IS NULL OR LENGTH(PI_REFERENCE) > 200 THEN
        RAISE_APPLICATION_ERROR(-20021, 'Supply FCR or UTR reference type and a nonblank reference up to 200 characters.');
      END IF;
      IF PI_INQUIRY_DATE IS NOT NULL OR PI_RECORD_COUNT IS NOT NULL THEN
        RAISE_APPLICATION_ERROR(-20022, 'Exact reference lookup does not accept date or record count.');
      END IF;
      -- Do not trim, parse as NUMBER, or replace the supplied reference.
      L_MODE := PI_REFERENCE_TYPE;
      IF L_MODE = 'FCR' THEN
        L_FCR_REFERENCE := PI_REFERENCE;
      ELSE
        L_UTR_REFERENCE := PI_REFERENCE;
      END IF;
      L_PAYMENT_LIMIT := 201; -- 201st group is an error signal to the wrapper.
    ELSE
      IF PI_INQUIRY_DATE IS NULL OR PI_RECORD_COUNT IS NULL
         OR PI_RECORD_COUNT < 1 OR PI_RECORD_COUNT > 200
         OR PI_RECORD_COUNT <> TRUNC(PI_RECORD_COUNT) THEN
        RAISE_APPLICATION_ERROR(-20023, 'List inquiry requires a date and an integer record count from 1 through 200.');
      END IF;
      L_MODE := 'LIST';
      L_DAY_START := TRUNC(PI_INQUIRY_DATE);
      L_DAY_END := L_DAY_START + 1;
      L_PAYMENT_LIMIT := PI_RECORD_COUNT + 1;
    END IF;

    -- Timestamp immediately before OPEN, available even for an empty cursor.
    -- This is an observation-start time, not a common SCN or completion receipt.
    PIO_OBSERVED_AT := TO_CHAR(SYSTIMESTAMP,
      'YYYY-MM-DD"T"HH24:MI:SS.FF6TZH:TZM', 'NLS_DATE_LANGUAGE=English');

    -- Static bound queries avoid concatenating operational identifiers into SQL.
    -- Sort/limit distinct payment identities before joining all host rows.
    IF L_MODE = 'FCR' THEN
      OPEN PIO_RESULT FOR
        WITH matched_payments AS (
          SELECT n.REFTXNNUMBER, n.UTR_REF_NO,
                 n.DATINITIATION, n.NUMAMOUNT_4038
          FROM PM_NEFT_TXN_LOG n
          WHERE n.REFTXNNUMBER = L_FCR_REFERENCE
            AND EXISTS (
              SELECT 1
              FROM PM_TXN_LOG h
              WHERE h.REF_TXN_NO = n.REFTXNNUMBER
                AND h.COD_ORG_BRN = PIO_ORG_BRN
                AND h.COD_ORG_BANK = PIO_ORG_BANK
            )
        ),
        payment_keys AS (
          -- MAX is only the sort key. All matched native rows are joined back.
          -- Conflicting amounts/dates/UTRs must remain visible to the wrapper.
          SELECT REFTXNNUMBER, MAX(DATINITIATION) AS SORT_INITIATION
          FROM matched_payments
          GROUP BY REFTXNNUMBER
        ),
        limited_keys AS (
          SELECT ordered_keys.*, ROWNUM AS DISCOVERY_ORDER
          FROM (
            SELECT REFTXNNUMBER, SORT_INITIATION
            FROM payment_keys
            ORDER BY SORT_INITIATION DESC NULLS LAST, REFTXNNUMBER DESC
          ) ordered_keys
          WHERE ROWNUM <= L_PAYMENT_LIMIT
        )
        SELECT CAST(p.REFTXNNUMBER AS VARCHAR2(4000)) AS PIO_REF_TXN_NO,
               TO_CHAR(h.COD_ORG_BRN, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS PIO_ORG_BRN,
               TO_CHAR(h.COD_ORG_BANK, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS PIO_ORG_BANK,
               TO_CHAR(h.REF_SUBSEQ_NO, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS REF_SUBSEQ_NO,
               CAST(p.UTR_REF_NO AS VARCHAR2(4000)) AS UTR_REF_NO,
               TO_CHAR(p.DATINITIATION, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DATINITIATION,
               CASE
                 WHEN SUBSTR(TO_CHAR(p.NUMAMOUNT_4038, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,'''), 1, 1) = '.'
                 THEN '0' || TO_CHAR(p.NUMAMOUNT_4038, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''')
                 ELSE TO_CHAR(p.NUMAMOUNT_4038, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''')
               END AS NUMAMOUNT_4038
        FROM limited_keys k
        JOIN matched_payments p ON p.REFTXNNUMBER = k.REFTXNNUMBER
        JOIN PM_TXN_LOG h ON h.REF_TXN_NO = p.REFTXNNUMBER
        WHERE h.COD_ORG_BRN = PIO_ORG_BRN
          AND h.COD_ORG_BANK = PIO_ORG_BANK
        ORDER BY k.DISCOVERY_ORDER, p.DATINITIATION DESC NULLS LAST,
                 p.UTR_REF_NO, p.NUMAMOUNT_4038, h.REF_SUBSEQ_NO;
    ELSIF L_MODE = 'UTR' THEN
      OPEN PIO_RESULT FOR
        WITH matched_payments AS (
          SELECT n.REFTXNNUMBER, n.UTR_REF_NO,
                 n.DATINITIATION, n.NUMAMOUNT_4038
          FROM PM_NEFT_TXN_LOG n
          WHERE n.UTR_REF_NO = L_UTR_REFERENCE
            AND EXISTS (
              SELECT 1
              FROM PM_TXN_LOG h
              WHERE h.REF_TXN_NO = n.REFTXNNUMBER
                AND h.COD_ORG_BRN = PIO_ORG_BRN
                AND h.COD_ORG_BANK = PIO_ORG_BANK
            )
        ),
        payment_keys AS (
          -- MAX is only the sort key. All matched native rows are joined back.
          -- Conflicting amounts/dates/UTRs must remain visible to the wrapper.
          SELECT REFTXNNUMBER, MAX(DATINITIATION) AS SORT_INITIATION
          FROM matched_payments
          GROUP BY REFTXNNUMBER
        ),
        limited_keys AS (
          SELECT ordered_keys.*, ROWNUM AS DISCOVERY_ORDER
          FROM (
            SELECT REFTXNNUMBER, SORT_INITIATION
            FROM payment_keys
            ORDER BY SORT_INITIATION DESC NULLS LAST, REFTXNNUMBER DESC
          ) ordered_keys
          WHERE ROWNUM <= L_PAYMENT_LIMIT
        )
        SELECT CAST(p.REFTXNNUMBER AS VARCHAR2(4000)) AS PIO_REF_TXN_NO,
               TO_CHAR(h.COD_ORG_BRN, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS PIO_ORG_BRN,
               TO_CHAR(h.COD_ORG_BANK, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS PIO_ORG_BANK,
               TO_CHAR(h.REF_SUBSEQ_NO, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS REF_SUBSEQ_NO,
               CAST(p.UTR_REF_NO AS VARCHAR2(4000)) AS UTR_REF_NO,
               TO_CHAR(p.DATINITIATION, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DATINITIATION,
               CASE
                 WHEN SUBSTR(TO_CHAR(p.NUMAMOUNT_4038, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,'''), 1, 1) = '.'
                 THEN '0' || TO_CHAR(p.NUMAMOUNT_4038, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''')
                 ELSE TO_CHAR(p.NUMAMOUNT_4038, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''')
               END AS NUMAMOUNT_4038
        FROM limited_keys k
        JOIN matched_payments p ON p.REFTXNNUMBER = k.REFTXNNUMBER
        JOIN PM_TXN_LOG h ON h.REF_TXN_NO = p.REFTXNNUMBER
        WHERE h.COD_ORG_BRN = PIO_ORG_BRN
          AND h.COD_ORG_BANK = PIO_ORG_BANK
        ORDER BY k.DISCOVERY_ORDER, p.DATINITIATION DESC NULLS LAST,
                 p.UTR_REF_NO, p.NUMAMOUNT_4038, h.REF_SUBSEQ_NO;
    ELSE
      OPEN PIO_RESULT FOR
        WITH matched_payments AS (
          SELECT n.REFTXNNUMBER, n.UTR_REF_NO,
                 n.DATINITIATION, n.NUMAMOUNT_4038
          FROM PM_NEFT_TXN_LOG n
          WHERE n.DATINITIATION >= L_DAY_START
            AND n.DATINITIATION < L_DAY_END
            AND EXISTS (
              SELECT 1
              FROM PM_TXN_LOG h
              WHERE h.REF_TXN_NO = n.REFTXNNUMBER
                AND h.COD_ORG_BRN = PIO_ORG_BRN
                AND h.COD_ORG_BANK = PIO_ORG_BANK
            )
        ),
        payment_keys AS (
          -- MAX is only the sort key. All matched native rows are joined back.
          -- Conflicting amounts/dates/UTRs must remain visible to the wrapper.
          SELECT REFTXNNUMBER, MAX(DATINITIATION) AS SORT_INITIATION
          FROM matched_payments
          GROUP BY REFTXNNUMBER
        ),
        limited_keys AS (
          SELECT ordered_keys.*, ROWNUM AS DISCOVERY_ORDER
          FROM (
            SELECT REFTXNNUMBER, SORT_INITIATION
            FROM payment_keys
            ORDER BY SORT_INITIATION DESC NULLS LAST, REFTXNNUMBER DESC
          ) ordered_keys
          WHERE ROWNUM <= L_PAYMENT_LIMIT
        )
        SELECT CAST(p.REFTXNNUMBER AS VARCHAR2(4000)) AS PIO_REF_TXN_NO,
               TO_CHAR(h.COD_ORG_BRN, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS PIO_ORG_BRN,
               TO_CHAR(h.COD_ORG_BANK, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS PIO_ORG_BANK,
               TO_CHAR(h.REF_SUBSEQ_NO, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') AS REF_SUBSEQ_NO,
               CAST(p.UTR_REF_NO AS VARCHAR2(4000)) AS UTR_REF_NO,
               TO_CHAR(p.DATINITIATION, 'YYYY-MM-DD"T"HH24:MI:SS', 'NLS_DATE_LANGUAGE=English') AS DATINITIATION,
               CASE
                 WHEN SUBSTR(TO_CHAR(p.NUMAMOUNT_4038, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,'''), 1, 1) = '.'
                 THEN '0' || TO_CHAR(p.NUMAMOUNT_4038, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''')
                 ELSE TO_CHAR(p.NUMAMOUNT_4038, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''')
               END AS NUMAMOUNT_4038
        FROM limited_keys k
        JOIN matched_payments p ON p.REFTXNNUMBER = k.REFTXNNUMBER
        JOIN PM_TXN_LOG h ON h.REF_TXN_NO = p.REFTXNNUMBER
        WHERE h.COD_ORG_BRN = PIO_ORG_BRN
          AND h.COD_ORG_BANK = PIO_ORG_BANK
        ORDER BY k.DISCOVERY_ORDER, p.DATINITIATION DESC NULLS LAST,
                 p.UTR_REF_NO, p.NUMAMOUNT_4038, h.REF_SUBSEQ_NO;
    END IF;
    L_CURSOR_OPENED := TRUE;
    -- 0 means OPEN succeeded, including zero matches. FETCH can still fail.
    -- Return success to HTTP only after complete fetch/validation/serialization.
    RETURN 0;
  EXCEPTION
    WHEN OTHERS THEN
      L_ERROR_CODE := SQLCODE;
      PIO_OBSERVED_AT := NULL;
      IF L_CURSOR_OPENED THEN
        BEGIN
          IF PIO_RESULT%ISOPEN THEN
            CLOSE PIO_RESULT;
          END IF;
        EXCEPTION
          WHEN OTHERS THEN NULL;
        END;
      END IF;
      LOG_ERROR(L_ERROR_CODE,
        'PK_BA_NEFT_EVIDENCE_INQ.AP_BA_NEFT_DISCOVERY_INQ');
      RETURN L_ERROR_CODE;
  END AP_BA_NEFT_DISCOVERY_INQ;


END PK_BA_NEFT_EVIDENCE_INQ;
/
