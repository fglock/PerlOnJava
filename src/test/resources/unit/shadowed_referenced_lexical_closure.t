use strict;
use warnings;
use Test::More;

package ShadowedLexicalClosureNotifier {
    sub new { my $class = shift; bless [ @_ ], $class }
    sub DESTROY { my $self = shift; ${ $self->[0] } .= $self->[1] }
}

{
    my $destroyed;
    my $notifier = ShadowedLexicalClosureNotifier->new( \$destroyed, 'old' );
    undef $notifier;
    is $destroyed, 'old', 'the first referenced lexical was destroyed';
}

{
    my $destroyed;
    my $make_notifier = sub {
        ShadowedLexicalClosureNotifier->new( \$destroyed, 'new' );
    };
    my $notifier = $make_notifier->();
    is $destroyed, undef,
        'a closure capture does not inherit a shadowed escaped lexical value';
}

done_testing;
