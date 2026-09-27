use v5.36;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

package ShadowedLexicalNotifier {
    sub new { my $class = shift; bless [ @_ ], $class }
    sub DESTROY { my $self = shift; ${ $self->[0] } .= $self->[1] }
}

{
    my $destroyed;
    my $notifier = ShadowedLexicalNotifier->new( \$destroyed, 'old' );
    undef $notifier;
    is $destroyed, 'old', 'the first referenced lexical was destroyed';
}

{
    my $destroyed;
    class ShadowedLexicalHolder {
        field $notifier;
        ADJUST {
            $notifier = ShadowedLexicalNotifier->new( \$destroyed, 'new' );
        }
    }

    my $object = ShadowedLexicalHolder->new;
    is $destroyed, undef,
        'a shadowing lexical does not inherit the escaped prior lexical value';
}

done_testing;
