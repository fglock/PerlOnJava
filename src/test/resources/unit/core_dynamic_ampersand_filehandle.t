use v5.36;
use Test::More;

undef *_;

sub install_core_alias {
    my ($name) = @_;
    no strict 'refs';
    *{"my$name"} = \&{"CORE::$name"};
}

sub test_proto_setup {
    my ($name) = @_;
    install_core_alias($name);
    my $prototype = prototype "CORE::$name";
    if ($prototype =~ /^([*\$]+);([*\$]+)$/) {
        my $max_args = length($1) + length($2);
        eval "&CORE::$name((1)x($max_args + 1))";
    }
}

test_proto_setup($_) for qw(alarm atan2 bind binmode);
install_core_alias('binmode');
{
    no strict 'subs';
    is &mybinmode(foo), undef,
        'a dynamically installed CORE::binmode alias receives its bareword filehandle argument';
}

done_testing;
