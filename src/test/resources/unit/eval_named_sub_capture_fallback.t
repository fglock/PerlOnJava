use strict;
use warnings;
use Test::More tests => 2;

my (
    $v00, $v01, $v02, $v03, $v04, $v05, $v06, $v07,
    $v08, $v09, $v10, $v11, $v12, $v13, $v14, $v15,
    $v16, $v17, $v18, $v19, $v20, $v21, $v22, $v23,
    $v24, $v25, $v26, $v27, $v28, $v29, $v30, $v31,
    $v32, $v33, $v34, $v35, $v36, $v37, $v38, $v39,
    $v40, $v41, $v42, $v43, $v44, $v45, $v46, $v47,
    $v48, $v49, $v50, $v51, $v52, $v53, $v54, $v55,
) = 0 .. 55;

my $body = join q{}, map { q{$v55 += 0;} } 1 .. 12000;
my $code = 'sub generated_capture { ' . $body . ' return $v55 }';

my $ok = eval $code;
is($@, '', 'large named sub inside eval compiles');

is(generated_capture(), 55, 'fallback-compiled eval sub captures late lexical');
