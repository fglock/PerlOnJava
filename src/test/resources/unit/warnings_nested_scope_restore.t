use strict;
use warnings;
use Test::More;

{
    no warnings 'uninitialized';
    my ($value, @warnings);
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $number = $value + 1;
    ok($number == 1 && !@warnings,
        'nested no warnings suppresses its lexical category');
}

my ($outer_value, @outer_warnings);
{
    local $SIG{__WARN__} = sub { push @outer_warnings, @_ };
    my $number = $outer_value + 1;
    is($number, 1, 'leaving no warnings restores nonfatal execution');
}
like(join('', @outer_warnings), qr/uninitialized value/i,
    'leaving no warnings restores the outer enabled category');

{
    use warnings FATAL => 'uninitialized';
    my $value;
    my $ok = eval { my $number = $value + 1; 1 };
    ok(!$ok && $@ =~ /uninitialized value/i,
        'nested fatal warnings apply inside their lexical block');
}

my ($after_fatal, @after_warnings);
my $after_ok;
{
    local $SIG{__WARN__} = sub { push @after_warnings, @_ };
    $after_ok = eval { my $number = $after_fatal + 1; 1 };
}
ok($after_ok && $@ eq '' && join('', @after_warnings) =~ /uninitialized value/i,
    'leaving fatal warnings restores the outer nonfatal category');

done_testing;
