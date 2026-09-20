use feature 'signatures';
use Test::More;

eval "#line 1 signature_malformed_diagnostics.t\nsub bad_numeric (123) { }";
like($@,
     qr/\AA signature parameter must start with '\$', '\@' or '%' at signature_malformed_diagnostics\.t line 1, near "\(1"\nsyntax error at signature_malformed_diagnostics\.t line 1, near "\(123"\n(?:Execution of signature_malformed_diagnostics\.t aborted due to compilation errors\.\n)?\z/,
     'an invalid signature parameter reports both recovered diagnostics');

eval "#line 1 signature_malformed_diagnostics.t\nsub bad_separator (\$a 123) { }";
like($@,
     qr/\AIllegal operator following parameter in a subroutine signature at signature_malformed_diagnostics\.t line 1, near "\(\$a 123"\nsyntax error at signature_malformed_diagnostics\.t line 1, near "\(\$a 123"\n(?:Execution of signature_malformed_diagnostics\.t aborted due to compilation errors\.\n)?\z/,
     'a missing signature comma reports both recovered diagnostics');

done_testing;
