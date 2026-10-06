use Test::More tests => 3;

{
    no warnings 'once';
    our $file = 'perl5_t/t/test.pl';
    my $open = \&CORE::open;

    ok $open->('file'),
        'CORE::open accepts a literal one-argument handle name';
    my $line = <file>;
    like($line, qr/^#/,
        'CORE::open opens the corresponding named handle');
    ok(CORE::close(*file), 'CORE::open named handle closes');
}
