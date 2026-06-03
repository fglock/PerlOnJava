use strict;
use warnings;
use Test::More;
use List::Util ();
use Scalar::Util qw(refaddr weaken);

{
    package ListUtilCleanup::Element;
    sub DESTROY {
        delete $main::PARENT{Scalar::Util::refaddr($_[0])};
        $main::DESTROYED++;
    }
}

{
    package ListUtilCleanup::Node;
    our @ISA = ('ListUtilCleanup::Element');

    sub tokens {
        @{ $_[0]->{children} };
    }

    sub serialize {
        my $self = shift;
        my @tokens = $self->tokens;
        my $out = '';

        foreach my $i (0 .. $#tokens) {
            my $token = $tokens[$i];
            $out .= $token->{content};
            if ($token->{damaged}) {
                my $last_index = $#tokens;
                my $last_line = List::Util::none {
                    $tokens[$_] and $tokens[$_]->{content} =~ /\n/
                } (($i + 1) .. $last_index);
                my $any_after = List::Util::any {
                    $tokens[$_]->{damaged}
                } (($i + 1) .. $#tokens);
                $out .= ($last_line ? 'L' : '') . ($any_after ? 'A' : '');
            }
        }

        $out;
    }
}

our (%PARENT, $DESTROYED);

{
    my $node = bless { children => [] }, 'ListUtilCleanup::Node';
    for my $i (1 .. 3) {
        my $token = bless {
            content => ($i == 2 ? "\n" : 'x'),
            damaged => ($i == 1),
        }, 'ListUtilCleanup::Element';
        push @{ $node->{children} }, $token;
        weaken($PARENT{refaddr($token)} = $node);
    }

    is($node->serialize, "x\nx", 'List::Util block can capture and inspect token array');
}

is(scalar(keys %PARENT), 0, 'List::Util block captures are released after call');
is($DESTROYED, 4, 'parent and children are destroyed after scope exit');

done_testing;
