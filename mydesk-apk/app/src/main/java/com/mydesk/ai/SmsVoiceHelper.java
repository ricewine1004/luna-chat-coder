package com.mydesk.ai;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.telephony.SmsManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SmsVoiceHelper {
    private SmsVoiceHelper() {}

    public static final class SmsRequest {
        public final String contactQuery;
        public final String message;

        SmsRequest(String contactQuery, String message) {
            this.contactQuery = contactQuery;
            this.message = message;
        }
    }

    public static final class ContactResult {
        public static final int FOUND = 1;
        public static final int NOT_FOUND = 2;
        public static final int AMBIGUOUS = 3;

        public final int status;
        public final String displayName;
        public final String phoneNumber;
        public final List<String> ambiguousNames;

        private ContactResult(int status, String displayName, String phoneNumber, List<String> ambiguousNames) {
            this.status = status;
            this.displayName = displayName == null ? "" : displayName;
            this.phoneNumber = phoneNumber == null ? "" : phoneNumber;
            this.ambiguousNames = ambiguousNames == null ? new ArrayList<>() : ambiguousNames;
        }

        static ContactResult found(String displayName, String phoneNumber) {
            return new ContactResult(FOUND, displayName, phoneNumber, null);
        }

        static ContactResult notFound() {
            return new ContactResult(NOT_FOUND, "", "", null);
        }

        static ContactResult ambiguous(List<String> names) {
            return new ContactResult(AMBIGUOUS, "", "", names);
        }
    }

    public static SmsRequest parse(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;

        String lower = text.toLowerCase(Locale.KOREAN);
        boolean looksLikeSend = lower.contains("보내") || lower.contains("전송") || lower.contains("문자해") || lower.contains("문자 줘") || lower.contains("문자줘");
        if (!looksLikeSend) return null;

        int smsIndex = indexOfSmsWord(lower);
        if (smsIndex < 0) return null;

        String beforeSms = text.substring(0, smsIndex).trim();
        Separator sep = findRecipientSeparator(beforeSms);
        if (sep == null) return null;

        String contact = beforeSms.substring(0, sep.index).trim();
        String message = beforeSms.substring(sep.index + sep.length).trim();
        if (contact.isEmpty() || message.isEmpty()) return null;

        contact = stripOuterQuotes(contact);
        message = stripQuoteParticle(message);
        message = stripOuterQuotes(message);
        if (contact.isEmpty() || message.isEmpty()) return null;

        return new SmsRequest(contact, message);
    }

    private static int indexOfSmsWord(String lower) {
        String[] words = {"문자메시지", "문자메세지", "문자 메시지", "문자 메세지", "문자", "sms"};
        int best = -1;
        for (String word : words) {
            int i = lower.lastIndexOf(word);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static Separator findRecipientSeparator(String text) {
        String[] separators = {"에게", "한테", "께"};
        Separator best = null;
        for (String s : separators) {
            int i = text.indexOf(s);
            if (i > 0 && (best == null || i < best.index)) best = new Separator(i, s.length());
        }
        return best;
    }

    private static String stripOuterQuotes(String s) {
        String out = s == null ? "" : s.trim();
        if (out.length() >= 2) {
            char first = out.charAt(0);
            char last = out.charAt(out.length() - 1);
            boolean quoted = (first == '"' && last == '"') || (first == '\'' && last == '\'')
                    || (first == '“' && last == '”') || (first == '‘' && last == '’');
            if (quoted) out = out.substring(1, out.length() - 1).trim();
        }
        return out;
    }

    private static String stripQuoteParticle(String message) {
        String out = message == null ? "" : message.trim();
        String base = null;
        if (out.endsWith("이라고")) base = out.substring(0, out.length() - 3).trim();
        else if (out.endsWith("라고")) base = out.substring(0, out.length() - 2).trim();

        // 따옴표로 실제 본문을 지정한 경우에만 뒤의 '라고/이라고'를 제거합니다.
        // '회의실로 오라고'처럼 본문 자체가 -라고로 끝나는 문장은 훼손하지 않습니다.
        if (base != null && endsWithClosingQuote(base)) out = base;
        return out;
    }

    private static boolean endsWithClosingQuote(String value) {
        if (value == null || value.isEmpty()) return false;
        char last = value.charAt(value.length() - 1);
        return last == '"' || last == '\'' || last == '”' || last == '’';
    }

    public static boolean needsAiPolish(String message) {
        String value = message == null ? "" : message.trim();
        if (value.isEmpty()) return false;
        return value.matches("(?s).*(?:는다고|ㄴ다고|다고|라고|이라고|한다고|달라고|자고|냐고)[.!?…]*$");
    }

    public static String fallbackPolish(String message) {
        String value = message == null ? "" : message.trim();
        if (value.isEmpty()) return value;

        String[][] common = {
                {"늦는다고", "늦어"},
                {"늦었다고", "늦었어"},
                {"도착한다고", "도착할게"},
                {"출발한다고", "출발할게"},
                {"연락한다고", "연락할게"},
                {"확인한다고", "확인할게"},
                {"보낸다고", "보낼게"},
                {"간다고", "갈게"},
                {"온다고", "올게"},
                {"끝났다고", "끝났어"},
                {"괜찮다고", "괜찮아"},
                {"고맙다고", "고마워"},
                {"미안하다고", "미안해"}
        };
        for (String[] pair : common) {
            if (value.endsWith(pair[0])) {
                return value.substring(0, value.length() - pair[0].length()) + pair[1] + ".";
            }
        }
        return value;
    }

    public static ContactResult findBestContact(Context context, String query) {
        String q = normalize(query);
        if (q.isEmpty()) return ContactResult.notFound();

        Map<Long, Candidate> byContact = new LinkedHashMap<>();
        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
        };

        try (Cursor c = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")) {
            if (c == null) return ContactResult.notFound();

            int idCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
            int nameCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numberCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER);
            int typeCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE);

            while (c.moveToNext()) {
                long id = c.getLong(idCol);
                String name = c.getString(nameCol);
                String number = c.getString(numberCol);
                int type = c.getInt(typeCol);
                if (name == null || number == null || number.trim().isEmpty()) continue;

                int rank = rankMatch(normalize(name), q);
                if (rank <= 0) continue;

                Candidate candidate = byContact.get(id);
                if (candidate == null) {
                    candidate = new Candidate(id, name, rank);
                    byContact.put(id, candidate);
                } else if (rank > candidate.rank) {
                    candidate.rank = rank;
                    candidate.displayName = name;
                }

                boolean mobile = type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE;
                if (candidate.phoneNumber == null || (mobile && !candidate.mobilePreferred)) {
                    candidate.phoneNumber = number;
                    candidate.mobilePreferred = mobile;
                }
            }
        } catch (Exception e) {
            return ContactResult.notFound();
        }

        if (byContact.isEmpty()) return ContactResult.notFound();

        int bestRank = 0;
        for (Candidate c : byContact.values()) bestRank = Math.max(bestRank, c.rank);

        List<Candidate> best = new ArrayList<>();
        for (Candidate c : byContact.values()) {
            if (c.rank == bestRank) best.add(c);
        }

        if (best.size() == 1) {
            Candidate c = best.get(0);
            return ContactResult.found(c.displayName, c.phoneNumber);
        }

        List<String> names = new ArrayList<>();
        for (Candidate c : best) {
            if (names.size() >= 3) break;
            names.add(c.displayName);
        }
        return ContactResult.ambiguous(names);
    }

    private static int rankMatch(String candidate, String query) {
        if (candidate.isEmpty() || query.isEmpty()) return 0;
        if (candidate.equals(query)) return 400;
        if (candidate.startsWith(query)) return 300;
        if (candidate.contains(query)) return 200;

        String candidateBase = stripRoleAndMeta(candidate);
        String queryBase = stripRoleAndMeta(query);
        if (!queryBase.isEmpty() && candidateBase.equals(queryBase)) return 180;
        if (!queryBase.isEmpty() && candidateBase.startsWith(queryBase)) return 170;
        return 0;
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.KOREAN)
                .replaceAll("[\\s\\-()\\[\\]{},.·ㆍ_/]", "")
                .trim();
    }

    private static String stripRoleAndMeta(String normalized) {
        String s = normalized == null ? "" : normalized;
        String[] roles = {"과장", "부장", "차장", "대리", "팀장", "실장", "주임", "사원", "대표", "사장", "이사", "상무", "전무", "님"};
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String role : roles) {
                if (s.endsWith(role) && s.length() > role.length()) {
                    s = s.substring(0, s.length() - role.length());
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    public static void sendSms(Context context, String phoneNumber, String message) {
        SmsManager sms = SmsManager.getDefault();
        ArrayList<String> parts = sms.divideMessage(message);
        if (parts.size() <= 1) {
            sms.sendTextMessage(phoneNumber, null, message, null, null);
        } else {
            sms.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
        }
    }

    private static final class Separator {
        final int index;
        final int length;
        Separator(int index, int length) {
            this.index = index;
            this.length = length;
        }
    }

    private static final class Candidate {
        final long contactId;
        String displayName;
        String phoneNumber;
        boolean mobilePreferred;
        int rank;

        Candidate(long contactId, String displayName, int rank) {
            this.contactId = contactId;
            this.displayName = displayName;
            this.rank = rank;
        }
    }
}
