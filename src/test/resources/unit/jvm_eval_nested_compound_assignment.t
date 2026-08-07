use strict;
use warnings;
use Test::More tests => 2;

our $stub_wrapper;
BEGIN {
    my $have6;
    my $sub = {};
    my $exported = {};
    $stub_wrapper = sub {
        my @c = caller 1;
        my $fullname = __PACKAGE__."::".(my $basename = shift);
        $sub->{$fullname} ? (return $sub->{$fullname}->(@_)) : (die "$fullname: Unable to replace symbol") if exists $sub->{$fullname};
        no strict 'refs';
        $sub->{$fullname} = Socket->can($basename) || eval { &{"Socket::$basename"}; 0 } || Socket->can($basename);
        $sub->{$fullname} ||= Socket6->can($basename) || eval { &{"Socket6::$basename"}; 0 } || Socket6->can($basename)
            if $have6 or !defined $have6 && eval { ($have6 ||= 0) ||= do { require Socket6; 1 } };
        if (my $code = $sub->{$fullname}) {
            no warnings qw(redefine prototype);
            eval { *{"$_\::$basename"} = $code foreach keys %{$exported->{$basename}}; *$fullname = $code }
                or warn "$fullname: On-The-Fly replacement failed: $@";
            my @res = ();
            eval { @res = $c[5] ? $code->(@_) : scalar $code->(@_); 1 }
                or do { (my $why = $@) =~ s/\s*at .* line \d.*//s; die "$why at $c[1] line $c[2]\n"; };
            return $c[5] ? @res : $res[0];
        }
        die "$basename is not a valid Socket macro and could not be imported at $c[1] line $c[2]\n";
    };
}

my $ok = eval { $stub_wrapper->('definitely_not_a_socket_symbol'); 1 };
ok(!$ok, 'unknown Socket symbol fails');
ok(length($@), 'nested eval/compound-assignment closure reports the failure');
