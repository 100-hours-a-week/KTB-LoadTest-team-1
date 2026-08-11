import { redirect } from 'next/navigation';

const LoginRedirectPage = async ({ searchParams }) => {
  const params = await searchParams;
  const redirectTarget = params?.redirect;
  const destination = typeof redirectTarget === 'string'
    ? `/?redirect=${encodeURIComponent(redirectTarget)}`
    : '/';

  redirect(destination);
};

export default LoginRedirectPage;
