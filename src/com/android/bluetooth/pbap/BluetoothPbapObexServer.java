/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.pbap;

import android.os.Message;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;

import javax.obex.ServerRequestHandler;
import javax.obex.ResponseCodes;
import javax.obex.ApplicationParameter;
import javax.obex.Operation;
import javax.obex.HeaderSet;

public class BluetoothPbapObexServer extends ServerRequestHandler {

    private static final String TAG = "BluetoothPbapObexServer";

    private static final int UUID_LENGTH = 16;

    private static final int LEGAL_PATH_NUM = 10;

    // The length of suffix of vcard name - ".vcf" is 5
    private static final int VCARD_NAME_SUFFIX_LENGTH = 5;

    // 128 bit UUID for PBAP
    private static final byte[] PBAP_TARGET = new byte[] {
            0x79, 0x61, 0x35, (byte)0xf0, (byte)0xf0, (byte)0xc5, 0x11, (byte)0xd8, 0x09, 0x66,
            0x08, 0x00, 0x20, 0x0c, (byte)0x9a, 0x66
    };

    private static final String[] LEGAL_PATH = {
            "/telecom", "/telecom/pb", "/telecom/ich", "/telecom/och", "/telecom/mch",
            "/telecom/cch", "/SIM1", "/SIM1/telecom", "/SIM1/telecom/ich", "/SIM1/telecom/och",
            "/SIM1/telecom/mch", "/SIM1/telecom/cch", "/SIM1/telecom/pb"
    };

    // missed call history
    private static final String MCH = "mch";

    // incoming call history
    private static final String ICH = "ich";

    // outgoing call history
    private static final String OCH = "och";

    // combined call history
    private static final String CCH = "cch";

    // phone book
    private static final String PB = "pb";

    private static final String ICH_PATH = "/telecom/ich";

    private static final String OCH_PATH = "/telecom/och";

    private static final String MCH_PATH = "/telecom/mch";

    private static final String CCH_PATH = "/telecom/cch";

    private static final String PB_PATH = "/telecom/pb";

    // type for list vcard objects
    private static final String TYPE_LISTING = "x-bt/vcard-listing";

    // type for get single vcard object
    private static final String TYPE_VCARD = "x-bt/vcard";

    //to indicate if need send body besides headers
    private static final int NEED_SEND_BODY = -1;

    // type for download all vcard objects
    private static final String TYPE_PB = "x-bt/phonebook";

    // The number of indexes in the phone book.
    private boolean mNeedPhonebookSize = false;

    // The number of missed calls that have not been checked on the PSE at the
    // point of the request. Only apply to "mch" case.
    private boolean mNeedNewMissedCallsNum = false;

    private int mMissedCallSize = 0;

    // record current path the client are browsing
    private String mCurrentPath = "";

    private long mConnectionId;

    private Handler mCallback = null;

    public static class ContentType {
        public static final int PHONEBOOK = 1;

        public static final int INCOMING_CALL_HISTORY = 2;

        public static final int OUTGOING_CALL_HISTORY = 3;

        public static final int MISSED_CALL_HISTORY = 4;

        public static final int COMBINED_CALL_HISTORY = 5;
    }

    public BluetoothPbapObexServer(Handler callback) {
        super();
        mConnectionId = -1;
        mCallback = callback;

        // set initial value when ObexServer created
        mMissedCallSize = BluetoothPbapService.getPhonebookSize(ContentType.MISSED_CALL_HISTORY);
    }

    @Override
    public int onConnect(final HeaderSet request, final HeaderSet reply) {
        try {
            byte[] uuid_tmp = (byte[])request.getHeader(HeaderSet.TARGET);
            if (uuid_tmp.length != UUID_LENGTH) {
                Log.w(TAG, "Wrong UUID length");
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            for (int i = 0; i < UUID_LENGTH; i++) {
                if (uuid_tmp[i] != PBAP_TARGET[i]) {
                    Log.w(TAG, "Wrong UUID");
                    return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        Message msg = Message.obtain(mCallback);
        msg.what = BluetoothPbapService.MSG_SESSION_ESTABLISHED;
        msg.sendToTarget();

        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public void onDisconnect(final HeaderSet req, final HeaderSet resp) {
        resp.responseCode = ResponseCodes.OBEX_HTTP_OK;
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothPbapService.MSG_SESSION_DISCONNECTED;
            msg.sendToTarget();
        }
    }

    @Override
    public int onPut(final Operation op) {
        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
    }

    @Override
    public int onSetPath(final HeaderSet request, final HeaderSet reply, final boolean backup,
            final boolean create) {
        if (BluetoothPbapService.DBG) {
            Log.d(TAG, "before setPath, mCurrentPath ==  " + mCurrentPath);
        }
        String current_path_tmp = mCurrentPath;
        String tmp_path = null;
        try {
            tmp_path = (String)request.getHeader(HeaderSet.NAME);
        } catch (IOException e) {
            Log.e(TAG, "Get name header fail");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        if (BluetoothPbapService.DBG) {
            Log.d(TAG, "backup=" + backup + " create=" + create + " name=" + tmp_path);
        }

        if (backup) {
            if (current_path_tmp.length() != 0) {
                current_path_tmp = current_path_tmp.substring(0,
                        current_path_tmp.lastIndexOf("/"));
            }
        } else {
            if (tmp_path == null) {
                current_path_tmp = "";
            } else {
                current_path_tmp = current_path_tmp + "/" + tmp_path;
            }
        }

        if ((current_path_tmp.length() != 0) && (!isLegalPath(current_path_tmp))) {
            if (create) {
                Log.w(TAG, "path create is forbidden!");
                return ResponseCodes.OBEX_HTTP_FORBIDDEN;
            } else {
                Log.w(TAG, "path is not legal");
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }
        }
        mCurrentPath = current_path_tmp;
        if (BluetoothPbapService.DBG) {
            Log.d(TAG, "after setPath, mCurrentPath ==  " + mCurrentPath);
        }
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public void onClose() {
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothPbapService.MSG_SERVERSESSION_CLOSE;
            msg.sendToTarget();
        }
    }

    @Override
    public int onGet(final Operation op) {
        HeaderSet request = null;
        HeaderSet reply = new HeaderSet();
        String type = "";
        String name = "";
        byte[] appParam = null;
        AppParamValue appParamValue = new AppParamValue();
        try {
            request = op.getReceivedHeader();
            type = (String)request.getHeader(HeaderSet.TYPE);
            name = (String)request.getHeader(HeaderSet.NAME);
            appParam = (byte[])request.getHeader(HeaderSet.APPLICATION_PARAMETER);
        } catch (IOException e) {
            Log.e(TAG, "request headers error");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        if (BluetoothPbapService.DBG) {
            Log.d(TAG, "OnGet type is " + type + " name is " + name);
        }

        // Accroding to specification,the name header could be omitted such as
        // sony erriccsonHBH-DS980

        // For "x-bt/phonebook" and "x-bt/vcard-listing":
        // if name == null, guess what carkit actually want from current path
        // For "x-bt/vcard":
        // We decide which kind of content client would like per current path

        boolean validName = true;
        if (name == null || name.length() == 0) {
            validName = false;
        }

        if (!validName || (validName && type.equals(TYPE_VCARD))) {
            if (BluetoothPbapService.DBG) {
                Log.i(TAG, "Guess what carkit actually want from current path (" + mCurrentPath
                        + ")");
            }
            if (mCurrentPath.compareTo(PB_PATH) == 0) {
                appParamValue.needTag = ContentType.PHONEBOOK;
            } else if (mCurrentPath.compareTo(ICH_PATH) == 0) {
                appParamValue.needTag = ContentType.INCOMING_CALL_HISTORY;
            } else if (mCurrentPath.compareTo(OCH_PATH) == 0) {
                appParamValue.needTag = ContentType.OUTGOING_CALL_HISTORY;
            } else if (mCurrentPath.compareTo(MCH_PATH) == 0) {
                appParamValue.needTag = ContentType.MISSED_CALL_HISTORY;
                mNeedNewMissedCallsNum = true;
            } else if (mCurrentPath.compareTo(CCH_PATH) == 0) {
                appParamValue.needTag = ContentType.COMBINED_CALL_HISTORY;
            } else {
                Log.w(TAG, "mCurrentpath is not valid path!!!");
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }
        } else {// we have weak name checking here to provide better
            // compatibility with other devices,although unique name such as
            // "pb.vcf" is required by SIG spec.
            if (name.contains(PB.subSequence(0, PB.length()))) {
                appParamValue.needTag = ContentType.PHONEBOOK;
                if (BluetoothPbapService.DBG) {
                    Log.v(TAG, "download phonebook request");
                }
            } else if (name.contains(ICH.subSequence(0, ICH.length()))) {
                appParamValue.needTag = ContentType.INCOMING_CALL_HISTORY;
                if (BluetoothPbapService.DBG) {
                    Log.v(TAG, "download incoming calls request");
                }
            } else if (name.contains(OCH.subSequence(0, OCH.length()))) {
                appParamValue.needTag = ContentType.OUTGOING_CALL_HISTORY;
                if (BluetoothPbapService.DBG) {
                    Log.v(TAG, "download outgoing calls request");
                }
            } else if (name.contains(MCH.subSequence(0, MCH.length()))) {
                appParamValue.needTag = ContentType.MISSED_CALL_HISTORY;
                mNeedNewMissedCallsNum = true;
                if (BluetoothPbapService.DBG) {
                    Log.v(TAG, "download missed calls request");
                }
            } else if (name.contains(CCH.subSequence(0, CCH.length()))) {
                appParamValue.needTag = ContentType.COMBINED_CALL_HISTORY;
                if (BluetoothPbapService.DBG) {
                    Log.v(TAG, "download combined calls request");
                }
            } else {
                Log.w(TAG, "Input name doesn't contain valid info!!!");
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }
        }

        if (!parseApplicationParameter(appParam, appParamValue)) {
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        // listing request
        if (type.equals(TYPE_LISTING)) {
            return pullVcardListing(appParam, appParamValue, reply, op);
        }
        // pull vcard entry request
        else if (type.equals(TYPE_VCARD)) {
            return pullVcardEntry(appParam, appParamValue, op, name, mCurrentPath);
        }
        // down load phone book request
        else if (type.equals(TYPE_PB)) {
            return pullPhonebook(appParam, appParamValue, reply, op, name);
        } else {
            Log.w(TAG, "unknown type request!!!");
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
    }

    /** check whether path is legal */
    private final boolean isLegalPath(final String str) {
        if (str.length() == 0) {
            return true;
        }
        for (int i = 0; i < LEGAL_PATH_NUM; i++) {
            if (str.equals(LEGAL_PATH[i])) {
                return true;
            }
        }
        return false;
    }

    private class AppParamValue {
        public int maxListCount;

        public int listStartOffset;

        public String searchValue;

        public String searchAttr;

        public int needTag;

        public boolean vcard21;

        public AppParamValue() {
            maxListCount = 0;
            listStartOffset = 0;
            searchValue = "";
            searchAttr = "";
            needTag = 0x00;
            vcard21 = true;
        }

        public void dump() {
            Log.i(TAG, "maxListCount=" + maxListCount + " listStartOffset=" + listStartOffset
                    + " searchValue=" + searchValue + " searchAttr=" + searchAttr + " needTag="
                    + needTag + " vcard21=" + vcard21);
        }
    }

    /** To parse obex application parameter */
    private final boolean parseApplicationParameter(final byte[] appParam,
            AppParamValue appParamValue) {
        int i = 0;
        boolean parseOk = true;
        while (i < appParam.length) {
            switch (appParam[i]) {
                case ApplicationParameter.TRIPLET_TAGID.FILTER_TAGID:
                    i += 2; // length and tag field in triplet
                    i += ApplicationParameter.TRIPLET_LENGTH.FILTER_LENGTH;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.ORDER_TAGID:
                    i += 2; // length and tag field in triplet
                    i += ApplicationParameter.TRIPLET_LENGTH.ORDER_LENGTH;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.SEARCH_VALUE_TAGID:
                    i += 1; // length field in triplet
                    for (int k = 1; k <= appParam[i]; k++) {
                        appParamValue.searchValue += Byte.toString(appParam[i + k]);
                    }
                    // length of search value is variable
                    i += appParam[i];
                    i += 1;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.SEARCH_ATTRIBUTE_TAGID:
                    i += 2;
                    appParamValue.searchAttr = Byte.toString(appParam[i]);
                    i += ApplicationParameter.TRIPLET_LENGTH.SEARCH_ATTRIBUTE_LENGTH;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.MAXLISTCOUNT_TAGID:
                    i += 2;
                    if (appParam[i] == 0 && appParam[i + 1] == 0) {
                        mNeedPhonebookSize = true;
                    } else {
                        int highValue = appParam[i] & 0xff;
                        int lowValue = appParam[i + 1] & 0xff;
                        appParamValue.maxListCount = highValue * 256 + lowValue;
                    }
                    i += ApplicationParameter.TRIPLET_LENGTH.MAXLISTCOUNT_LENGTH;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.LISTSTARTOFFSET_TAGID:
                    i += 2;
                    int highValue = appParam[i] & 0xff;
                    int lowValue = appParam[i + 1] & 0xff;
                    appParamValue.listStartOffset = highValue * 256 + lowValue;
                    i += ApplicationParameter.TRIPLET_LENGTH.LISTSTARTOFFSET_LENGTH;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.FORMAT_TAGID:
                    i += 2;// length field in triplet
                    if (Byte.toString(appParam[i]).compareTo("0") != 0) {
                        appParamValue.vcard21 = false;
                    }
                    i += ApplicationParameter.TRIPLET_LENGTH.FORMAT_LENGTH;
                    break;
                default:
                    parseOk = false;
                    if (BluetoothPbapService.DBG) {
                        Log.e(TAG, "Parse Application Parameter error");
                    }
                    break;
            }
        }

        if (BluetoothPbapService.DBG) {
            appParamValue.dump();
        }
        return parseOk;
    }

    /** Form and Send an XML format String to client for Phone book listing */
    private final int sendVcardListingXml(final int type, final Operation op,
            final int maxListCount, final int listStartOffset, final String searchValue,
            String searchAttr) {
        OutputStream out = null;
        StringBuilder result = new StringBuilder();
        int itemsFound = 0;
        result.append("<?xml version=\"1.0\"?>");
        result.append("<!DOCTYPE vcard-listing SYSTEM \"vcard-listing.dtd\">");
        result.append("<vCard-listing version=\"1.0\">");

        // Phonebook listing request
        if (type == ContentType.PHONEBOOK) {
            // begin of search by name
            if (searchAttr.compareTo("0") == 0) {
                ArrayList<String> nameList = BluetoothPbapService.getPhonebookNameList();
                int requestSize = nameList.size() >= maxListCount ? maxListCount : nameList.size();
                int startPoint = listStartOffset;
                int endPoint = startPoint + requestSize;
                if (endPoint > nameList.size()) {
                    endPoint = nameList.size();
                }

                if (BluetoothPbapService.DBG) {
                    Log.d(TAG, "search by name, size=" + requestSize + " offset=" + listStartOffset
                            + " searchValue=" + searchValue);
                }

                // if searchValue if not set by client,provide the entire
                // list by name
                if (searchValue == null || searchValue.trim().length() == 0) {
                    for (int j = startPoint; j < endPoint; j++) {
                        result.append("<card handle=\"" + j + ".vcf\" name=\"" + nameList.get(j)
                                + "\"" + "/>");
                        itemsFound++;
                    }
                } else {
                    for (int j = startPoint; j < endPoint; j++) {
                        // only find the name which begins with the searchValue
                        if (nameList.get(j).startsWith(searchValue.trim())) {
                            // TODO: PCE not work with it
                            itemsFound++;
                            result.append("<card handle=\"" + j + ".vcf\" name=\""
                                    + nameList.get(j) + "\"" + "/>");
                        }
                    }
                }
            }// end of search by name
            // begin of search by number
            else if (searchAttr.compareTo("1") == 0) {
                ArrayList<String> numberList = BluetoothPbapService.getPhonebookNumberList();
                int requestSize = numberList.size() >= maxListCount ? maxListCount : numberList
                        .size();
                int startPoint = listStartOffset;
                int endPoint = startPoint + requestSize;
                if (endPoint > numberList.size()) {
                    endPoint = numberList.size();
                }

                if (BluetoothPbapService.DBG) {
                    Log.d(TAG, "search by number, size=" + requestSize + " offset="
                            + listStartOffset + " searchValue=" + searchValue);
                }
                // if searchValue if not set by client,provide the entire
                // list by number
                if (searchValue == null || searchValue.trim().length() == 0) {
                    for (int j = startPoint; j < endPoint; j++) {
                        result.append("<card handle=\"" + j + ".vcf\" number=\""
                                + numberList.get(j) + "\"" + "/>");
                        itemsFound++;
                    }
                } else {
                    for (int j = startPoint; j < endPoint; j++) {
                        // only find the name which begins with the searchValue
                        if (numberList.get(j).startsWith(searchValue.trim())) {
                            itemsFound++;
                            result.append("<card handle=\"" + j + ".vcf\" number=\""
                                    + numberList.get(j) + "\"" + "/>");
                        }
                    }
                }
            }// end of search by number
            else {
                return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
            }
        }
        // Call history listing request
        else {
            ArrayList<String> nameList = BluetoothPbapService.getCallLogList(type);
            int requestSize = nameList.size() >= maxListCount ? maxListCount : nameList.size();
            int startPoint = listStartOffset;
            int endPoint = startPoint + requestSize;
            if (endPoint > nameList.size()) {
                endPoint = nameList.size();
            }
            if (BluetoothPbapService.DBG) {
                Log.d(TAG, "call log list, size=" + requestSize + " offset=" + listStartOffset);
            }
            for (int j = startPoint; j < endPoint; j++) {
                // listing object begin with 1.vcf
                result.append("<card handle=\"" + (j + 1) + ".vcf\" name=\"" + nameList.get(j)
                    + "\"" + "/>");
                itemsFound++;
            }
        }
        result.append("</vCard-listing>");

        if (BluetoothPbapService.DBG) {
            Log.d(TAG, "itemsFound =" + itemsFound);
        }

        return pushBytes(op, result.toString());
    }

    /**
     * Function to send obex header back to client such as get phonebook size
     * request
     */
    private final int pushHeader(final Operation op, final HeaderSet reply) {
        OutputStream outputStream = null;

        if (BluetoothPbapService.DBG) {
            Log.d(TAG, "Push Header");
            Log.d(TAG, reply.toString());
        }

        int ret = ResponseCodes.OBEX_HTTP_OK;
        try {
            op.sendHeaders(reply);
            outputStream = op.openOutputStream();
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            ret = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        } finally {
            if (!closeStream(outputStream, op)) {
                ret = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            }
        }
        return ret;
    }

    /** Function to send vcard data to client */
    private final int pushBytes(final Operation op, final String string) {
        if (string == null) {
            return ResponseCodes.OBEX_HTTP_OK;
        }
        byte[] filebytes;
        int ret = ResponseCodes.OBEX_HTTP_OK;
        OutputStream outputStream = null;
        if (BluetoothPbapService.DBG) {
            Log.d(TAG, "Send Data");
            Log.d(TAG, string);
        }
        try {
            outputStream = op.openOutputStream();
            filebytes = string.getBytes();
            outputStream.write(filebytes);
        } catch (IOException e) {
            Log.e(TAG, "open outputstrem failed" + e.toString());
            ret = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        } finally {
            if (!closeStream(outputStream, op)) {
                ret = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            }
        }
        return ret;
    }

    private final int handleAppParaForResponse(AppParamValue appParamValue, HeaderSet reply,
            final Operation op) {
        int size = 0;
        byte[] misnum = new byte[1];
        ApplicationParameter ap = new ApplicationParameter();

        // In such case, PCE only want the number of index.
        // So response not contain any Body header.
        if (mNeedPhonebookSize) {
            mNeedPhonebookSize = false;

            byte[] pbsize = new byte[2];
            size = BluetoothPbapService.getPhonebookSize(appParamValue.needTag);

            pbsize[0] = (byte)((size / 256) & 0xff);// HIGH VALUE
            pbsize[1] = (byte)((size % 256) & 0xff);// LOW VALUE
            ap.addAPPHeader(ApplicationParameter.TRIPLET_TAGID.PHONEBOOKSIZE_TAGID,
                    ApplicationParameter.TRIPLET_LENGTH.PHONEBOOKSIZE_LENGTH, pbsize);

            if (mNeedNewMissedCallsNum) {
                int nmnum = size - mMissedCallSize;
                mMissedCallSize = size;

                nmnum = nmnum > 0 ? nmnum : 0;
                misnum[0] = (byte)nmnum;
                ap.addAPPHeader(ApplicationParameter.TRIPLET_TAGID.NEWMISSEDCALLS_TAGID,
                        ApplicationParameter.TRIPLET_LENGTH.NEWMISSEDCALLS_LENGTH, misnum);
            }
            reply.setHeader(HeaderSet.APPLICATION_PARAMETER, ap.getAPPparam());

            if (BluetoothPbapService.DBG) {
                Log.d(TAG, "Send back Phonebook size only, without body info! Size= " + size);
            }

            return pushHeader(op, reply);
        }

        // Only apply to "mch" download/listing.
        // NewMissedCalls is used only in the response, together with Body
        // header.
        if (mNeedNewMissedCallsNum) {
            mNeedNewMissedCallsNum = false;
            size = BluetoothPbapService.getPhonebookSize(ContentType.MISSED_CALL_HISTORY);
            int nmnum = size - mMissedCallSize;
            mMissedCallSize = size;

            nmnum = nmnum > 0 ? nmnum : 0;
            misnum[0] = (byte)nmnum;
            ap.addAPPHeader(ApplicationParameter.TRIPLET_TAGID.NEWMISSEDCALLS_TAGID,
                    ApplicationParameter.TRIPLET_LENGTH.NEWMISSEDCALLS_LENGTH, misnum);
            reply.setHeader(HeaderSet.APPLICATION_PARAMETER, ap.getAPPparam());

            // Only Specifies the headers, not write for now, will write to PCE
            // together with Body
            try {
                op.sendHeaders(reply);
            } catch (IOException e) {
                Log.e(TAG, e.toString());
                return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            }
        }
        return NEED_SEND_BODY;
    }

    private final int pullVcardListing(byte[] appParam, AppParamValue appParamValue,
            HeaderSet reply, final Operation op) {
        String searchAttr = appParamValue.searchAttr.trim();

        if (searchAttr == null || searchAttr.length() == 0) {
            // If searchAttr is not set by PCE, set default value per spec.
            appParamValue.searchAttr = "0";
            if (BluetoothPbapService.DBG) {
                Log.i(TAG, "searchAttr is not set by PCE, assume search by name by default");
            }
        } else if (searchAttr.compareTo("0") != 0 && searchAttr.compareTo("1") != 0) {
            Log.w(TAG, "search attr not supported");
            if (searchAttr.compareTo("2") == 0) {
                // search by sound is not supported currently
                Log.w(TAG, "do not support search by sound");
                return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
            }
            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
        } else {
            if (BluetoothPbapService.DBG) {
                Log.i(TAG, "searchAttr is valid: " + searchAttr);
            }
        }

        int ret1 = handleAppParaForResponse(appParamValue, reply, op);
        if (ret1 != NEED_SEND_BODY) {
            return ret1;
        }

        int ret = sendVcardListingXml(appParamValue.needTag, op, appParamValue.maxListCount,
                appParamValue.listStartOffset, appParamValue.searchValue, appParamValue.searchAttr);
        return ret;
    }

    private final int pullVcardEntry(byte[] appParam, AppParamValue appParamValue,
            final Operation op, final String name, final String current_path) {
        boolean vcard21 = true;
        int intIndex = 0;

        if (name == null || name.length() < VCARD_NAME_SUFFIX_LENGTH) {
            if (BluetoothPbapService.DBG) {
                Log.d(TAG, "Name is Null, or the length of name < 5 !");
            }
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
        String strIndex = name.substring(0, name.length() - VCARD_NAME_SUFFIX_LENGTH + 1);
        if (strIndex.trim().length() != 0) {
            try {
                intIndex = Integer.parseInt(strIndex);
            } catch (NumberFormatException e) {
                Log.e(TAG, "catch number format exception " + e.toString());
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }
        }

        int ret = ResponseCodes.OBEX_HTTP_OK;
        if (appParamValue.needTag == 0) {
            Log.w(TAG, "wrong path!");
            ret = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        } else if (appParamValue.needTag == ContentType.PHONEBOOK) {
            if (intIndex < 0
                    || intIndex >= BluetoothPbapService.getPhonebookSize(appParamValue.needTag))
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            if (intIndex >= 0) {
                // For PB_PATH, 0.vcf is the phone number of this phone.
                String str = BluetoothPbapService.getPhonebook(appParamValue.needTag, intIndex,
                        vcard21);
                ret = pushBytes(op, str);
            }
        } else {
            if (intIndex <= 0
                    || intIndex > BluetoothPbapService.getPhonebookSize(appParamValue.needTag)) {
                Log.w(TAG, "The requested vcard is not acceptable! name= " + name);
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            // For others (ich/och/cch/mch), 0.vcf is meaningless, and must
            // begin from 1.vcf
            if (intIndex >= 1) {
                String str = BluetoothPbapService.getPhonebook(appParamValue.needTag, intIndex - 1,
                        vcard21);
                ret = pushBytes(op, str);
            }
        }
        return ret;
    }

    private final int pullPhonebook(byte[] appParam, AppParamValue appParamValue, HeaderSet reply,
            final Operation op, final String name) {
        boolean vcard21 = true;
        StringBuilder result = new StringBuilder();

        // code start for passing PTS3.2 TC_PSE_PBD_BI_01_C
        if (name != null) {
            int dotIndex = name.indexOf(".");
            String vcf = "vcf";
            if (dotIndex >= 0 && dotIndex <= name.length()) {
                if (name.regionMatches(dotIndex + 1, vcf, 0, vcf.length()) == false) {
                    Log.w(TAG, "name is not .vcf");
                    return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
                }
            }
        } // code end for passing PTS3.2 TC_PSE_PBD_BI_01_C

        int ret1 = handleAppParaForResponse(appParamValue, reply, op);
        if (ret1 != NEED_SEND_BODY) {
            return ret1;
        }

        int pbSize = BluetoothPbapService.getPhonebookSize(appParamValue.needTag);
        int requestSize = pbSize >= appParamValue.maxListCount ? appParamValue.maxListCount
                : pbSize;
        int startPoint = appParamValue.listStartOffset;
        int endPoint = startPoint + requestSize;
        if (endPoint > pbSize) {
            endPoint = pbSize;
        }
        for (int pos = startPoint; pos < endPoint; pos++) {
            String str = BluetoothPbapService.getPhonebook(appParamValue.needTag, pos, vcard21);
            result.append(str);
        }
        return pushBytes(op, result.toString());
    }

    private boolean closeStream(final OutputStream out, final Operation op) {
        boolean returnvalue = true;
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "outputStream close failed" + e.toString());
            returnvalue = false;
        }
        try {
            if (op != null) {
                op.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "oeration close failed" + e.toString());
            returnvalue = false;
        }
        return returnvalue;
    }

    public final void setConnectionID(final long id) {
        if ((id < -1) || (id > 0xFFFFFFFFL)) {
            throw new IllegalArgumentException("Illegal Connection ID");
        }
        mConnectionId = id;
    }

    /**
     * Retrieves the connection ID that is being used in the present connection.
     * This method will return -1 if no connection ID is being used.
     *
     * @return the connection id being used or -1 if no connection ID is being
     *         used
     */
    public final long getConnectionID() {
        return mConnectionId;
    }

    // Reserved for future use. In case PSE challenge PCE and PCE input wrong
    // session key.
    public final void onAuthenticationFailure(final byte[] userName) {
    }
}
