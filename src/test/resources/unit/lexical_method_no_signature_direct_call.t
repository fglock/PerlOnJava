use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';
use Test::More tests => 4;

class LexicalMethodWithoutSignature {
    my method private_value {
        return 'private-result';
    }

    my method signed_value ($suffix = '') {
        return 'signed-result' . $suffix;
    }

    method call_private_value {
        return private_value($self);
    }

    method call_signed_value {
        return signed_value($self, '!');
    }
}

is(LexicalMethodWithoutSignature->new->call_private_value,
    'private-result', 'method can directly call a lexical method without a signature');
ok(!LexicalMethodWithoutSignature->can('private_value'),
    'directly called lexical method remains absent from the package stash');
is(LexicalMethodWithoutSignature->new->call_signed_value,
    'signed-result!', 'direct call passes the invocant to a lexical method with a signature');
ok(!LexicalMethodWithoutSignature->can('signed_value'),
    'signed lexical method remains absent from the package stash');
