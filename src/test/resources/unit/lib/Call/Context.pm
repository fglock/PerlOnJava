package Call::Context;

use strict;
use warnings;

our $VERSION = '0.05';
my $OVERLOADED;

sub must_be_list {
    return _must_be_list(0);
}

sub _must_be_list {
    return if (caller 2)[5];

    $OVERLOADED ||= eval q{
        package Call::Context::X;
        use overload ( q<""> => \\&_spew );
        1;
    };

    die Call::Context::X->_new($_[0]);
}

package Call::Context::X;

sub _new {
    my ($class, $accept_void_yn) = @_;
    my ($sub, $ctx) = (caller 3)[3, 5];
    my (undef, $filename, $line, $caller_sub) = caller 4;

    $ctx = defined($ctx) ? 'scalar' : 'void';
    return bless(\"$sub called in non-list ($ctx) context from $caller_sub (line $line of $filename)", $class);
}

sub _spew { ${ $_[0] } }

1;
