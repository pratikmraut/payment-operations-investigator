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
13/09/2026  1.0         pmraut          PO02 NEFT Evidence Inquiry API
-------------------------------------------------------------------------------*/

DELETE FROM api_txn_data WHERE serviceid = 'PO02' AND channelid = 'API';

DELETE FROM xface_txn_chnl_xref WHERE cod_service = 'PO02' AND cod_chnl_id = 'API';

DELETE FROM api_defaultservice_mapping WHERE serviceid = 'PO02' AND channelid = 'API';

DELETE FROM api_twentyfourbyseven_config WHERE serviceid = 'PO02' AND channelid = 'API';

DELETE FROM api_channel_service_master WHERE serviceid = 'PO02' AND channelid = 'API';

DELETE FROM sm_task_defn WHERE cod_task = 'PO02';

INSERT INTO api_txn_data (channelid, serviceid, reqresflg, fieldname, dataname, validatorclassname, enricherclassname, mandatoryflg, format, eagerflg)
VALUES ('API', 'PO02', 'REQ', 'XfaceNEFTEvidenceInquiryRequestDTO/referenceTransactionNumber', 'REF_NO', '', '', 'Y', '', 'N');

INSERT INTO api_txn_data (channelid, serviceid, reqresflg, fieldname, dataname, validatorclassname, enricherclassname, mandatoryflg, format, eagerflg)
VALUES ('API', 'PO02', 'REQ', 'XfaceNEFTEvidenceInquiryRequestDTO/originatingBranchCode', 'BRN_CODE', '', '', 'Y', '', 'N');

INSERT INTO api_txn_data (channelid, serviceid, reqresflg, fieldname, dataname, validatorclassname, enricherclassname, mandatoryflg, format, eagerflg)
VALUES ('API', 'PO02', 'REQ', 'XfaceNEFTEvidenceInquiryRequestDTO/originatingBankCode', 'BANK_CODE', '', '', 'Y', '', 'N');

INSERT INTO xface_txn_chnl_xref (cod_service, cod_chnl_id, cod_task)
VALUES ('PO02', 'API', 'PO02');

INSERT INTO api_channel_service_master (channelid, serviceid, flgcomposite, compositefieldname)
VALUES ('API', 'PO02', 'N', '');

INSERT INTO api_twentyfourbyseven_config (channelid, serviceid, flgsupported)
VALUES ('API', 'PO02', 'Y');

INSERT INTO api_defaultservice_mapping (serviceid, defaultserviceid, channelid, defaultchannelid)
VALUES ('PO02', 'PO02', 'API', 'API');

INSERT INTO sm_task_defn (cod_task, cod_task_cat, cod_task_rv, cod_task_ec, cod_txn_mnemonic, flg_tdef1, flg_tdef2, amt_txn_ofln_lmt, amt_txn_onln_lmt, auth_bitmap, cod_proc, cod_proc_contra, flg_src_from, flg_sc_from, cod_proc_sc, cod_proc_sc_contra, sc_contra_gl_acct, tot_sc_acct, flg_auto_auth, stl_proc_code, flg_oltp, cod_chn_id, txt_auth_sign, flg_profit)
VALUES ('PO02', '0', 'PO02', '0', 0, 2243, 33, 999999999900, 999999999900, 0, 0, 0, '', '', 0, 0, '', '', 'Y', null, 'N', 'API', '', 'N');

COMMIT;
