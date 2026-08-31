use Test::More;

our $line = '';
my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    chop ($line .= <DATA>);
}
is($line, '', 'a direct core chop call on unopened DATA leaves its argument empty');
is_deeply(\@warnings, [], 'a direct core chop call does not warn about unopened DATA');

done_testing;
