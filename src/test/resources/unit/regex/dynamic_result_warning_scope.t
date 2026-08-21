use strict;
use warnings;
use re 'eval';

print "1..4\n";

{
    no warnings 'uninitialized';
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, shift };
    my $ok = eval { 'x' =~ /(??{})/; 1 };
    print $ok && !@warnings
        ? "ok 1 - dynamic undef result honors lexical no warnings\n"
        : "not ok 1 - dynamic undef result honors lexical no warnings: $@ @warnings\n";
}

{
    use warnings NONFATAL => 'uninitialized';
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, shift };
    'x' =~ /(??{})/;
    print grep(/uninitialized value/i, @warnings)
        ? "ok 2 - dynamic undef result emits enabled warning\n"
        : "not ok 2 - dynamic undef result emits enabled warning\n";
}

{
    use warnings FATAL => 'uninitialized';
    eval { 'x' =~ /(??{})/ };
    print $@ =~ /uninitialized value/i
        ? "ok 3 - dynamic undef result honors lexical fatal warning\n"
        : "not ok 3 - dynamic undef result honors lexical fatal warning: $@\n";
}

{
    use warnings FATAL => 'uninitialized';
    my ($ok, @warnings);
    {
        no warnings 'uninitialized';
        local $SIG{__WARN__} = sub { push @warnings, shift };
        $ok = eval q{ use re 'eval'; 'x' =~ /(??{})/; 1 };
    }
    print $ok && !@warnings
        ? "ok 4 - eval STRING preserves disabled callback warning scope\n"
        : "not ok 4 - eval STRING preserves disabled callback warning scope: $@ @warnings\n";
}
