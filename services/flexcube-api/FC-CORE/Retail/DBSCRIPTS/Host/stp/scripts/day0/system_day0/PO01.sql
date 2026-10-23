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
----------  ----------- --------------- ---------------------------------------
14/09/2026  1.0         pmraut          PO01 NEFT Payment Discovery Inquiry API
-------------------------------------------------------------------------------*/

DELETE FROM api_txn_data WHERE serviceid = 'PO01' AND channelid = 'API';

DELETE FROM xface_txn_chnl_xref WHERE cod_service = 'PO01' AND cod_chnl_id = 'API';

DELETE FROM api_defaultservice_mapping WHERE serviceid = 'PO01' AND channelid = 'API';

DELETE FROM api_twentyfourbyseven_config WHERE serviceid = 'PO01' AND channelid = 'API';

DELETE FROM api_channel_service_master WHERE serviceid = 'PO01' AND channelid = 'API';

DELETE FROM sm_task_defn WHERE cod_task = 'PO01';

/*
 * Branch and bank are mandatory. The remaining fields are two exclusive
 * request modes, enforced by XfaceNEFTPaymentDiscoveryInquiryWrapperImpl:
 * FCR/UTR reference lookup, or inquiry date plus record count list lookup.
 */
INSERT INTO api_txn_data (channelid, serviceid, reqresflg, fieldname, dataname, validatorclassname, enricherclassname, mandatoryflg, format, eagerflg)
VALUES ('API', 'PO01', 'REQ', 'XfaceNEFTPaymentDiscoveryInquiryRequestDTO/originatingBranchCode', 'BRN_CODE', '', '', 'Y', '', 'N');

INSERT INTO api_txn_data (channelid, serviceid, reqresflg, fieldname, dataname, validatorclassname, enricherclassname, mandatoryflg, format, eagerflg)
VALUES ('API', 'PO01', 'REQ', 'XfaceNEFTPaymentDiscoveryInquiryRequestDTO/originatingBankCode', 'BANK_CODE', '', '', 'Y', '', 'N');

INSERT INTO api_txn_data (channelid, serviceid, reqresflg, fieldname, dataname, validatorclassname, enricherclassname, mandatoryflg, format, eagerflg)
VALUES ('API', 'PO01', 'REQ', 'XfaceNEFTPaymentDiscoveryInquiryRequestDTO/referenceType', 'STRING_3', '', '', 'N', '', 'N');

INSERT INTO api_txn_data (channelid, serviceid, reqresflg, fieldname, dataname, validatorclassname, enricherclassname, mandatoryflg, format, eagerflg)
VALUES ('API', 'PO01', 'REQ', 'XfaceNEFTPaymentDiscoveryInquiryRequestDTO/reference', 'STRING_4000', '', '', 'N', '', 'N');

INSERT INTO api_txn_data (channelid, serviceid, reqresflg, fieldname, dataname, validatorclassname, enricherclassname, mandatoryflg, format, eagerflg)
VALUES ('API', 'PO01', 'REQ', 'XfaceNEFTPaymentDiscoveryInquiryRequestDTO/inquiryDate', 'STRING_10', '', '', 'N', '', 'N');

INSERT INTO api_txn_data (channelid, serviceid, reqresflg, fieldname, dataname, validatorclassname, enricherclassname, mandatoryflg, format, eagerflg)
VALUES ('API', 'PO01', 'REQ', 'XfaceNEFTPaymentDiscoveryInquiryRequestDTO/recordCount', 'NUMBER_3', '', '', 'N', '', 'N');

INSERT INTO xface_txn_chnl_xref (cod_service, cod_chnl_id, cod_task)
VALUES ('PO01', 'API', 'PO01');

INSERT INTO api_channel_service_master (channelid, serviceid, flgcomposite, compositefieldname)
VALUES ('API', 'PO01', 'N', '');

INSERT INTO api_twentyfourbyseven_config (channelid, serviceid, flgsupported)
VALUES ('API', 'PO01', 'Y');

INSERT INTO api_defaultservice_mapping (serviceid, defaultserviceid, channelid, defaultchannelid)
VALUES ('PO01', 'PO01', 'API', 'API');

INSERT INTO sm_task_defn (cod_task, cod_task_cat, cod_task_rv, cod_task_ec, cod_txn_mnemonic, flg_tdef1, flg_tdef2, amt_txn_ofln_lmt, amt_txn_onln_lmt, auth_bitmap, cod_proc, cod_proc_contra, flg_src_from, flg_sc_from, cod_proc_sc, cod_proc_sc_contra, sc_contra_gl_acct, tot_sc_acct, flg_auto_auth, stl_proc_code, flg_oltp, cod_chn_id, txt_auth_sign, flg_profit)
VALUES ('PO01', '0', 'PO01', '0', 0, 2243, 33, 999999999900, 999999999900, 0, 0, 0, '', '', 0, 0, '', '', 'Y', null, 'N', 'API', '', 'N');

COMMIT;
