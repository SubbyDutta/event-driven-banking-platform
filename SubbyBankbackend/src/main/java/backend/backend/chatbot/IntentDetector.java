package backend.backend.chatbot;

import org.springframework.stereotype.Component;

@Component
public class IntentDetector {

    public ChatIntent detect(String query) {

        String q = query.toLowerCase().trim();

        if (

                q.contains("balance") ||
                        q.contains("account balance") ||
                        q.contains("available balance") ||
                        q.contains("current balance") ||

                        q.contains("last transaction") ||
                        q.contains("recent transaction") ||
                        q.contains("recent transactions") ||
                        q.contains("latest transaction") ||
                        q.contains("latest transactions") ||

                        q.contains("last sent") ||
                        q.contains("money sent") ||
                        q.contains("sent money") ||

                        q.contains("last received") ||
                        q.contains("money received") ||
                        q.contains("received money") ||

                        q.contains("loan status") ||
                        q.contains("my loan status") ||
                        q.contains("active loan") ||
                        q.contains("current loan")
        ) {
            return new ChatIntent(IntentType.DIRECT_DATA, q);
        }

        if (

                q.contains("account details") ||
                        q.contains("bank account details") ||
                        q.contains("account summary") ||
                        q.contains("bank summary") ||

                        q.contains("my profile") ||
                        q.contains("my details") ||
                        q.contains("user details") ||
                        q.contains("profile summary") ||

                        q.contains("loan details") ||
                        q.contains("loan summary") ||
                        q.contains("loan information")
        ) {
            return new ChatIntent(IntentType.ANALYTICAL, q);
        }

        if (

                q.contains("how to") ||
                        q.contains("steps") ||
                        q.contains("process") ||

                        q.contains("transfer money") ||
                        q.contains("send money") ||
                        q.contains("money transfer") ||

                        q.contains("apply loan") ||
                        q.contains("loan apply") ||
                        q.contains("loan process") ||

                        q.contains("loan rejected") ||
                        q.contains("why loan rejected") ||
                        q.contains("loan rejection reason")
        ) {
            return new ChatIntent(IntentType.PROCEDURAL, q);
        }

        return new ChatIntent(IntentType.DISALLOWED, q);
    }
}
