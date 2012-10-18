/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.gallery3d.exif;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ExifParserTest extends ExifXmlDataTestCase {
    private static final String TAG = "ExifParserTest";

    public ExifParserTest(int imgRes, int xmlRes) {
        super(imgRes, xmlRes);
    }

    public ExifParserTest(String imgPath, String xmlPath) {
        super(imgPath, xmlPath);
    }

    private List<Map<Short, String>> mGroundTruth;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mGroundTruth = ExifXmlReader.readXml(getXmlParser());
    }

    public void testParse() throws Exception {
        ExifParser parser = ExifParser.parse(getImageInputStream());
        int event = parser.next();
        while (event != ExifParser.EVENT_END) {
            switch (event) {
                case ExifParser.EVENT_START_OF_IFD:
                    break;
                case ExifParser.EVENT_NEW_TAG:
                    ExifTag tag = parser.getTag();
                    if (!tag.hasValue()) {
                        parser.registerForTagValue(tag);
                    } else {
                        checkTag(tag);
                    }
                    break;
                case ExifParser.EVENT_VALUE_OF_REGISTERED_TAG:
                    tag = parser.getTag();
                    if (tag.getDataType() == ExifTag.TYPE_UNDEFINED) {
                        byte[] buf = new byte[tag.getComponentCount()];
                        parser.read(buf);
                        tag.setValue(buf);
                    }
                    checkTag(tag);
                    break;
            }
            event = parser.next();
        }
    }

    private void checkTag(ExifTag tag) {
        // Ignore offset tags since the ground-truth from exiftool doesn't have it.
        // We can verify it by examining the sub-IFD or thumbnail itself.
        if (ExifTag.isSubIfdOffsetTag(tag.getTagId())) return;

        // TODO: Test MakerNote and UserComment
        if (tag.getTagId() == ExifTag.TAG_MAKER_NOTE
                || tag.getTagId() == ExifTag.TAG_USER_COMMENT) return;

        String truthString = mGroundTruth.get(tag.getIfd()).get(tag.getTagId()).trim();

        if (truthString == null) {
            fail(String.format("Unknown Tag %02x", tag.getTagId()) + ", " + getImageTitle());
        }

        String dataString = tag.valueToString().trim();
        assertEquals(String.format("Tag %02x", tag.getTagId()) + ", " + getImageTitle(),
                truthString, dataString);
    }

    private void parseOneIfd(int ifd, int options)
            throws IOException, ExifInvalidFormatException {
        Map<Short, String> expectedResult = mGroundTruth.get(ifd);
        int numOfTag = 0;
        ExifParser parser = ExifParser.parse(getImageInputStream(), options);
        int event = parser.next();
        while(event != ExifParser.EVENT_END) {
            switch (event) {
                case ExifParser.EVENT_START_OF_IFD:
                    assertEquals(getImageTitle(), ifd, parser.getCurrentIfd());
                    break;
                case ExifParser.EVENT_NEW_TAG:
                    ExifTag tag = parser.getTag();
                    // The exiftool doesn't provide MakerNote tag
                    if (!ExifTag.isSubIfdOffsetTag(tag.getTagId())
                            && tag.getTagId() != ExifTag.TAG_MAKER_NOTE) numOfTag++;
                    if (tag.hasValue()) {
                        checkTag(tag);
                    } else {
                        parser.registerForTagValue(tag);
                    }
                    break;
                case ExifParser.EVENT_VALUE_OF_REGISTERED_TAG:
                    tag = parser.getTag();
                    if (tag.getDataType() == ExifTag.TYPE_UNDEFINED) {
                        byte[] buf = new byte[tag.getComponentCount()];
                        parser.read(buf);
                        tag.setValue(buf);
                    }
                    checkTag(tag);
                    break;
                case ExifParser.EVENT_COMPRESSED_IMAGE:
                case ExifParser.EVENT_UNCOMPRESSED_STRIP:
                    fail("Invalid Event type: " + event + ", " + getImageTitle());
                    break;
            }
            event = parser.next();
        }
        assertEquals(expectedResult.size(), numOfTag);
    }

    public void testOnlyExifIfd() throws Exception {
        parseOneIfd(IfdId.TYPE_IFD_EXIF, ExifParser.OPTION_IFD_EXIF);
    }

    public void testOnlyIfd0() throws Exception {
        parseOneIfd(IfdId.TYPE_IFD_0, ExifParser.OPTION_IFD_0);
    }

    public void testOnlyIfd1() throws Exception {
        parseOneIfd(IfdId.TYPE_IFD_1, ExifParser.OPTION_IFD_1);
    }

    public void testOnlyInteroperabilityIfd() throws Exception {
        parseOneIfd(IfdId.TYPE_IFD_INTEROPERABILITY, ExifParser.OPTION_IFD_INTEROPERABILITY);
    }

    public void testOnlyReadSomeTag() throws Exception {
        ExifParser parser = ExifParser.parse(getImageInputStream(), ExifParser.OPTION_IFD_0);
        int event = parser.next();
        boolean isTagFound = false;
        while (event != ExifParser.EVENT_END) {
            switch (event) {
                case ExifParser.EVENT_START_OF_IFD:
                    assertEquals(getImageTitle(), IfdId.TYPE_IFD_0, parser.getCurrentIfd());
                    break;
                case ExifParser.EVENT_NEW_TAG:
                    ExifTag tag = parser.getTag();
                    if (tag.getTagId() == ExifTag.TAG_MODEL) {
                        if (tag.hasValue()) {
                            isTagFound = true;
                            checkTag(tag);
                        } else {
                            parser.registerForTagValue(tag);
                        }
                        parser.skipRemainingTagsInCurrentIfd();
                    }
                    break;
                case ExifParser.EVENT_VALUE_OF_REGISTERED_TAG:
                    tag = parser.getTag();
                    assertEquals(getImageTitle(), ExifTag.TAG_MODEL, tag.getTagId());
                    checkTag(tag);
                    isTagFound = true;
                    break;
            }
            event = parser.next();
        }
        assertTrue(getImageTitle(), isTagFound);
    }

    public void testReadThumbnail() throws Exception {
        ExifParser parser = ExifParser.parse(getImageInputStream(),
                ExifParser.OPTION_IFD_1 | ExifParser.OPTION_THUMBNAIL);

        int event = parser.next();
        Bitmap bmp = null;
        boolean mIsContainCompressedImage = false;
        while (event != ExifParser.EVENT_END) {
            switch (event) {
                case ExifParser.EVENT_NEW_TAG:
                    ExifTag tag = parser.getTag();
                    if (tag.getTagId() == ExifTag.TAG_COMPRESSION) {
                        if (tag.getUnsignedShort(0) == ExifTag.Compression.JPEG) {
                            mIsContainCompressedImage = true;
                        }
                    }
                    break;
                case ExifParser.EVENT_COMPRESSED_IMAGE:
                    int imageSize = parser.getCompressedImageSize();
                    byte buf[] = new byte[imageSize];
                    parser.read(buf);
                    bmp = BitmapFactory.decodeByteArray(buf, 0, imageSize);
                    break;
            }
            event = parser.next();
        }
        if (mIsContainCompressedImage) {
            assertNotNull(getImageTitle(), bmp);
        }
    }
}
