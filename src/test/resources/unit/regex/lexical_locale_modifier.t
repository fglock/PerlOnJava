use strict;
use warnings;
use Test::More tests => 14;

is("" . qr/(\w+)/, '(?^:(\w+))',
   'default regexp has no locale modifier');

{
    use locale;
    is("" . qr/(\w+)/, '(?^l:(\w+))',
       'plain use locale propagates l');

    {
        no locale;
        is("" . qr/(\w+)/, '(?^:(\w+))',
           'nested no locale removes l');
    }

    is("" . qr/(\w+)/, '(?^l:(\w+))',
       'locale propagation resumes after nested no locale');
}

{
    use locale ':ctype';
    is("" . qr/(\w+)/, '(?^l:(\w+))',
       'locale ctype category propagates l');
}

{
    use locale ':collate';
    is("" . qr/(\w+)/, '(?^:(\w+))',
       'locale collate category does not affect regexp character classes');
}

{
    use locale ':characters';
    is("" . qr/(\w+)/, '(?^l:(\w+))',
       'locale characters category propagates l');
}

{
    use locale ':not_characters';
    is("" . qr/(\w+)/, '(?^u:(\w+))',
       'locale not_characters excludes l and enables Unicode semantics');
}

my $locale_qr;
{
    use locale ':ctype';
    $locale_qr = qr/(\w+)/;
}

is("" . qr/^$locale_qr$/, '(?^:^(?^l:(\w+))$)',
   'interpolation retains compiled locale provenance');
ok('abc' =~ /^$locale_qr$/,
   'interpolated locale regexp matches');
is($1, 'abc',
   'interpolated locale regexp preserves captures');

my $explicit = qr/(?l:(\w+))/;
is("$explicit", '(?^:(?l:(\w+)))',
   'explicit inline locale modifier behavior is retained');
ok('xyz' =~ /^$explicit$/,
   'explicit inline locale regexp matches');
is($1, 'xyz',
   'explicit inline locale regexp preserves captures');
